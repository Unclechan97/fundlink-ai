# FundLink Agent Loop — 闭环设计方案

> 2026-08-08 | v1.0

---

## 一、目标

```
用户贴接口文档 → 自动完成: 解析 → 写入 → 渲染验证 → 干跑测试 → 修正(如有错) → 发布
人在环路中的角色: 最终确认者，而非逐字段修正者
```

---

## 二、整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     AgentLoopOrchestrator                        │
│                                                                  │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐     │
│  │ ANALYZE  │ → │ VALIDATE │ → │ DRYRUN   │ → │ PUBLISH  │     │
│  │ 解析+写入  │   │ 渲染验证  │   │ 干跑测试  │   │ 发布      │     │
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘     │
│       ↑              ↓               ↓                          │
│       │         ┌──────────┐   ┌──────────┐                    │
│       └──────── │ DIAGNOSE │ ← │ TESTGEN  │                    │
│        (修正后    │ 诊断修正  │   │ 生成测试  │                    │
│         重解析)   └──────────┘   └──────────┘                    │
│                                                                  │
│  状态机驱动 | 重试上限 3 轮 | 每轮 SSE 推送进度                   │
│  修正成功 → RAG 知识回写 | 3 轮失败 → 人工接管                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、各阶段设计

### 3.1 ANALYZE — 解析 + 写入

复用现有 `RequirementAgent.analyze()` + `ConfigWriter.writeAll()`。

新增：`analyze()` 接收 `previousErrors` 参数，修正轮次时将上一轮诊断结果注入 Prompt：

```
## 上一轮验证失败，请修正:
- 阶段: VALIDATE, 错误: FreeMarker 未定义变量 userInfo.annualIncom
  诊断: sourcePath 疑似截断
  建议: 检查 annualIncome 的 sourcePath 是否完整

请基于建议修正，重新输出完整 JSON。
```

### 3.2 VALIDATE — 渲染验证 (TemplateValidator)

```
1. TestGen 生成 previewData
2. 调 POST /api/admin/templates/{id}/preview
3. 检查: 渲染结果非空 + code != 50002 + 所有映射字段在输出中存在
4. 失败 → 提取缺失字段 → 送 DiagnosisAgent
```

### 3.3 DRYRUN — 干跑测试 (FlowDryRunner)

```
对 CONDITION 节点的每条出边:
  1. 创建临时 mock (rule_name: TEST_{taskId}_{sourceCode}_{branchId})
  2. 临时启用 mock (enabled=1)
  3. 调 FlowEngine.executeSync(flowDefId, inputData)
  4. 验证:
     - FlowResult.status == SUCCESS
     - contextData 含预期 key (走到哪条分支)
  5. 禁用 mock (enabled=0，不删除——留痕)
  6. 记录结果

全部分支通过 → 进入 PUBLISH
任一失败 → 送 DiagnosisAgent
```

### 3.4 DIAGNOSE — 诊断 (DiagnosisAgent 重写)

双层：
1. 规则引擎 pre-filter：FreeMarker 变量缺失 / SpEL 语法 / 数据源超时 / enumMap 空值
2. LLM 深度诊断：规则覆盖不到时，结构化 JSON 输出 rootCause + fixSuggestion + confidence

### 3.5 DECISION_POINT — 人工决策

```
SSE Event: decision_required
  summary: "模板渲染失败: education 字段缺失"
  options: ["让AI修正", "跳过", "人工编辑", "终止"]

前端 POST /api/ai/loop/decide { taskId, decision }
  RETRY → 回到 ANALYZE (round+1)
  SKIP → 继续下一阶段
  EDIT_AND_RETRY → 前端传回人工修改后的数据，继续
  ABORT → 终止，保留已写入配置
```

### 3.6 PUBLISH — 发布

所有验证通过后，弹确认框："是否发布流程？"

确认 → PUT /api/admin/flows/{id}/publish → PUBLISHED

---

## 四、TestGen Agent 重写

```
输入: flowDsl, fieldMappings, providerCode
输出:
  previewData: {...}           // 模板渲染用
  testCases: [{                // 每个 CONDITION 分支 1 个
    name: "风控A级-放款成功",
    targetBranch: "e6",
    inputData: {...},          // FlowEngine 输入
    mockRules: [{              // 本条需要的 mock
      sourceCode: "RISK",
      responseJson: {"level":"A","score":85}
    }]
  }]
```

---

## 五、状态机

```
PENDING → ANALYZE → VALIDATE → DRYRUN → DECISION_POINT → PUBLISH → PUBLISHED
                ↑         ↓          ↓           │
                │     DIAGNOSE ←──┘           RETRY (round<3)
                │         │                   ABORT (round>=3)
                └─────────┘
```

---

## 六、SSE 事件协议

```
phase:start        {phase, round, maxRounds}
phase:progress     {phase, message}
phase:complete     {phase, summary}
phase:error        {phase, message}
decision_required  {type, summary, options}
task:complete      {status, summary}
task:failed        {status, error, rounds}
```

前端：先 POST 创建 task 拿到 taskId，再 GET /api/ai/loop/{taskId}/stream 建立 SSE。

---

## 七、Mock 留痕约定

```
命名: TEST_{taskId}_{sourceCode}_{branchId}
操作: 干跑后 enabled=0 (禁用，不删除)
清理: 下次同 taskId 重试前，先禁用该 task 所有旧 mock
```

---

## 八、数据模型

### ai_task 新增字段

```sql
ALTER TABLE ai_task ADD COLUMN current_round INT DEFAULT 0;
ALTER TABLE ai_task ADD COLUMN max_rounds INT DEFAULT 3;
ALTER TABLE ai_task ADD COLUMN flow_type VARCHAR(20);
ALTER TABLE ai_task ADD COLUMN document_text TEXT;
ALTER TABLE ai_task ADD COLUMN current_result JSON;
ALTER TABLE ai_task ADD COLUMN status VARCHAR(30) DEFAULT 'PENDING';
```

### ai_agent_trace 新增字段

```sql
ALTER TABLE ai_agent_trace ADD COLUMN phase VARCHAR(20);
ALTER TABLE ai_agent_trace ADD COLUMN agent_type VARCHAR(30);
ALTER TABLE ai_agent_trace ADD COLUMN input_summary VARCHAR(500);
ALTER TABLE ai_agent_trace ADD COLUMN output_summary TEXT;
ALTER TABLE ai_agent_trace ADD COLUMN token_usage JSON;
ALTER TABLE ai_agent_trace ADD COLUMN duration_ms INT;
ALTER TABLE ai_agent_trace ADD COLUMN success TINYINT(1);
ALTER TABLE ai_agent_trace ADD COLUMN error_msg TEXT;
```

---

## 九、组件清单

### 复用
| 组件 | 改动 |
|------|------|
| RequirementAgent | analyze() 加 previousErrors 参数 |
| ConfigWriter | 已幂等，无需改动 |
| PromptBuilder | 无需改动 |
| PromptEnhancer | 已修复，无需改动 |
| QwenProvider | 已修复，无需改动 |
| field-catalog.yml | 无需改动 |

### 重写
| 组件 | 现状 → 目标 |
|------|------------|
| TestGenAgentImpl | 丢弃 LLM 返回值 → 真正解析 + 按分支生成 |
| DiagnosisAgentImpl | LLM 分支返回占位 → 结构化 JSON + 规则+LLM 双层 |

### 新增
| 组件 | 职责 |
|------|------|
| AgentLoopOrchestrator | 状态机 + SSE 推送 + 重试控制 |
| TemplateValidator | 调 Preview API + 验证渲染结果 |
| FlowDryRunner | 临时 mock 注入 + executeSync + 分支覆盖检查 |
| LoopTracer | RoundTrace 记录 + RAG 知识回写 |
| AiTaskMapper/Entity | 任务持久化 |
| AiAgentTraceMapper/Entity | 轨迹持久化 |

---

## 十、前端改动

保留现有 Copilot.jsx（手动模式）。新增自动模式组件：

```
┌─────────────────────────────────────────────────┐
│  AI Copilot                         [手动/自动] │
│                                                 │
│  ┌─ 进度 ──────────────────────────────────────┐│
│  │ ● ANALYZE ── ● WRITE ── ◐ VALIDATE ── ○ DRY ││
│  │ Round 1/3                                   ││
│  └─────────────────────────────────────────────┘│
│                                                 │
│  ┌─ 阶段输出 (可折叠) ─────────────────────────┐│
│  │ ✅ ANALYZE: 15 字段, 9 节点                 ││
│  │ ✅ WRITE: Provider=20, Template=17           ││
│  │ ⚠ VALIDATE: education 字段缺失              ││
│  │   [让AI修正] [跳过] [人工编辑] [终止]       ││
│  └─────────────────────────────────────────────┘│
│                                                 │
│  ┌─ 字段映射 ──────────────────────────────────┐│
│  │ ... (现有表格，可编辑)                       ││
│  └─────────────────────────────────────────────┘│
│                                                 │
│  ┌─ 流程图 ────────────────────────────────────┐│
│  │ ... (现有 React Flow)                        ││
│  └─────────────────────────────────────────────┘│
└─────────────────────────────────────────────────┘
```

---

## 十一、HTTP 客户端策略

### 现状
- `QwenProvider` → `java.net.http.HttpClient` (modern)
- `ConfigWriter` → `HttpURLConnection` ×6 (legacy)
- `PromptEnhancer` → `HttpURLConnection` ×2 (legacy)

### 决策
- **连接池**：不做。调用全是串行 + localhost，零收益。
- **封装 FundLinkClient**：该做，但不是现在。等写 TemplateValidator / FlowDryRunner 时自然提取，避免面向猜测设计。
- **统一换 HttpClient**：同上，提取时顺手换。

---

## 十二、SmartRouter

```
简单/requirement/testgen → DeepSeek (便宜 10x)
诊断/complex → Claude Haiku (精确)
兜底 → Qwen
Fallback chain: deepseek → claude → qwen → 报错
```

实施：实现 `DeepSeekProvider`（OpenAI 兼容格式），`LlmGatewayImpl` 接入 `SmartRouter.select(taskType)`。

---

## 十三、实施路线

```
Week 1: 引擎核心
  Day 1: AiTask + AiAgentTrace Entity/Mapper
  Day 2: AgentLoopOrchestrator 状态机 + SSE
  Day 3: TemplateValidator + TestGenAgent 重写
  Day 4: FlowDryRunner + DiagnosisAgent 重写
  Day 5: LoopTracer + 集成测试

Week 2: 联调 + 收尾
  Day 1: 前端 SSE 消费 + 进度面板
  Day 2: 决策点交互
  Day 3: 映射表/流程图嵌入 Loop + SmartRouter + DeepSeekProvider
  Day 4: 调试边界情况
  Day 5: 提交 + 更新 PROJECT_STATUS.md
```

---

## 十四、已知风险

| 风险 | 对策 |
|------|------|
| Preview API 不用 field mappings | 两层验证：Preview 快筛 + executeSync 真跑 |
| FlowEngine 无 trace | 用 outputKey 间接验证分支覆盖 |
| FundLink 无 unpublish API | 失败后保持 published，前端标记 |
| SEND_TO_FUND 是 stub | 端到端测试受限，待 FundLink 侧实现 |
| LLM 输出不稳定 (sourcePath 截断) | parseSafely 加字段名校验，Validator 可发现 |
| 3 轮仍失败 | 交人工，展示每轮诊断记录 |
