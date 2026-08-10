# FundLink AI — 项目功能文档

> 2026-08-10 | 全量功能清单 + 架构详解

---

## 一、系统概览

```
┌──────────────────────────────────────────────────────┐
│  fundlink-ui (React 19, 端口 3000)                    │
│  AI Copilot: 手动模式 + Agent Loop 自动闭环           │
└────────────┬─────────────────────────────────────────┘
             │ HTTP + SSE
             ▼
┌──────────────────────────────────────────────────────┐
│  fundlink-ai (Spring Boot 3.2, 端口 8081)            │
│                                                      │
│  ┌─────────────────────────────────────────────┐     │
│  │ AgentLoopOrchestrator (状态机引擎)            │     │
│  │  ANALYZE → VALIDATE → DRYRUN → DECISION     │     │
│  │     ↑                   ↓         ↓         │     │
│  │     └── DIAGNOSE ←──────┘     PUBLISH       │     │
│  └─────────────────────────────────────────────┘     │
│                                                      │
│  ┌──────────────┐  ┌────────────┐  ┌─────────────┐  │
│  │ LLM Gateway  │  │ ConfigWriter│  │ RAG Gateway │  │
│  │ (多Provider) │  │ (写FundLink)│  │ (知识检索)  │  │
│  └──────┬───────┘  └────────────┘  └──────┬──────┘  │
│         │                                 │          │
└─────────┼─────────────────────────────────┼──────────┘
          │ LLM 调用                         │ HTTP
          ▼                                 ▼
┌──────────────────┐          ┌──────────────────────┐
│ LLM Providers    │          │ RAG Service (8000)   │
│ SiliconFlow/Qwen/│          │ Python + Qdrant      │
│ DeepSeek         │          │ 语义检索 + 知识写回  │
└──────┬───────────┘          └──────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────┐
│  fundlink (Spring Boot, 端口 8080)                    │
│  信贷核心: Provider / Template / Flow / Mock /       │
│           Preview API / Dry-Run API                  │
└──────────────────────────────────────────────────────┘
```

### 数据库

| 数据库 | 用途 |
|--------|------|
| MySQL (fundlink) | 业务数据: provider, template, flow, mapping, mock_rules |
| MySQL (fundlink-ai) | AI 数据: ai_task, ai_agent_trace, ai_llm_audit, ai_feedback |
| Qdrant (6333) | RAG 向量存储 |

---

## 二、Agent Loop 闭环引擎

### 2.1 状态机

```
ANALYZE  ──成功──▶ VALIDATE ──成功──▶ DRYRUN ──成功──▶ DECISION (PUBLISH_CONFIRM) ──▶ PUBLISH ──▶ PUBLISHED
   │                  │                  │                      │
   └──失败──▶          └──失败──▶          └──失败──▶              │
            DIAGNOSE ◀───────────────────────────────────────────┘
               │
               ├── round < maxRounds-1 → 自动 RETRY → ANALYZE
               └── 耗尽 → DECISION (SKIP / EDIT_AND_RETRY / ABORT)
```

**关键行为:**

- 最大重试轮数: `fundlink.loop.max-rounds`（默认 3）
- 自动重试: 前 N-1 轮失败后直接重试，不进决策点
- 人工决策: 最后一轮失败 + 全部通过时的发布确认
- 中断机制: `POST /cancel` 设置取消标记 → while 循环下轮检查 → cleanup

### 2.2 各阶段详解

**ANALYZE — 接口文档解析**
```
RequirementAgent.analyze(documentText, providerCode, flowType, previousErrors)
  ├─ FlowTypeDetector.detect()         → 自动判定 LOAN/CREDIT/REPAY
  ├─ RagGateway.search()              → 检索历史成功案例
  ├─ PromptBuilder.build()            → 组装完整 Prompt(系统提示词+字段目录+RAG案例+修正建议)
  ├─ LlmGateway.chat()                → 调用 LLM
  ├─ parseSafely()                     → JsonNode 逐字段安全解析
  └─ FieldCompletenessGuard.check()   → 校验字段全覆盖
```

**VALIDATE — 模板渲染验证**
```
TestGenAgent.generate()               → LLM 生成 previewData + testCases
TemplateValidator.validate()          → 调 Preview API + 字段检查
```

**DRYRUN — 流程干跑**
```
FlowDryRunner.dryRun()                → Mock 注入 + 逐分支 dry-run + 冒烟测试
```

**DIAGNOSE — 双层诊断**
```
DiagnosisAgent.diagnose(phase, error, context)
  ├─ 规则引擎 (5条, confidence ≥ 0.7 直接返回):
  │   字段缺少映射(0.92) / FreeMarker(0.85) / SpEL(0.82) / 数据源(0.80) / enumMap(0.78)
  └─ 规则覆盖不到 → LLM 深度诊断
```

### 2.3 SSE 事件流

| 事件名 | 数据 | 触发 |
|--------|------|------|
| `phase:start` | `{phase, round, maxRounds}` | 阶段开始 |
| `phase:progress` | `{phase, message}` | 阶段内进度 |
| `phase:complete` | `{phase, summary}` | 阶段完成 |
| `phase:error` | `{phase, message}` | 阶段报错 |
| `decision_required` | `{type, summary, options}` | 需要人工决策 |
| `task:complete` | `{status, summary}` | 任务成功 |
| `task:failed` | `{status, error, rounds}` | 任务失败/中断 |
| `ping` | `{ts}` | 心跳(25s) |

---

## 三、LLM 网关

### 3.1 路由链

```
LlmGatewayImpl.chat(request)
  buildChain():
    1. request.provider → 加入链首
    2. SmartRouter.select(taskType) → 查 task-routing map → 加入链
    3. fallback-chain → 加入链尾
    去重 → [qwen, siliconflow, deepseek]
  遍历链 → 成功返回 | 失败审计 → 下一个
```

### 3.2 Provider

统一的 `OpenAiCompatibleProvider`——所有 Provider 共用同一套 `chat()` 逻辑(`/chat/completions` + Bearer Auth)。差异仅 `name`/`baseUrl`/`apiKey`/`defaultModel`，由 `LlmProviderConfig` 注入。新增 Provider 只需 yml + `@Bean`。

### 3.3 SmartRouter

```yaml
router:
  default-provider: qwen
  default-model: qwen-plus
  fallback-chain: qwen,siliconflow,deepseek
  task-routing:                    # 按任务类型分配 model
    requirement: { provider: siliconflow, model: Qwen/Qwen3-8B }
    diagnosis:   { provider: deepseek,    model: deepseek-chat }
```

### 3.4 审计

每次 LLM 调用 → `ai_llm_audit` (provider, model, token_input/output, cost, latency, success, error_msg)

---

## 四、ConfigWriter — FreeMarker 模板生成

### 4.1 算法

```
fieldMappings → buildTree(解析点号+数组路径) → writeNode(递归输出JSON)
  ├─ "a.b.c" → 嵌套对象
  ├─ "arr[].x" → <#list> 循环
  └─ sourcePath="" → "" 占位
```

| sourcePath | transform | 模板输出 |
|-----------|-----------|---------|
| `loanInfo.amount` | `formatAmount` | `"${formatAmount(loanInfo.amount)}"` |
| `userInfo.realName` | null | `"${userInfo.realName}"` |
| `""` | — | `""` (字面空字符串) |

### 4.2 幂等

- Provider: `findByCode → 复用`
- Template: `findByCode → PUT 更新` (防止旧遗漏残留)
- FieldMapping: `先删后建`
- Flow: `findByCode → 复用`

---

## 五、API

### 手动模式

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/analyze` | 解析文档 → RequirementResult |
| POST | `/api/ai/suggest-mappings` | 字段映射建议 |
| POST | `/api/ai/apply` | 审核后写入 FundLink |

### 闭环控制

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/loop` | 创建任务 → taskId |
| GET | `/api/ai/loop/{id}/stream` | SSE 事件流 |
| POST | `/api/ai/loop/{id}/decide` | 提交决策 |
| POST | `/api/ai/loop/{id}/cancel` | 中断任务 |
| GET | `/api/ai/loop/{id}` | 查询状态 |
| GET | `/api/ai/loop/{id}/result` | 当前解析结果 |

---

## 六、配置项

```yaml
fundlink:
  llm:
    connect-timeout-seconds: 30
    request-timeout-minutes: 10
    providers: { siliconflow, qwen, deepseek }
    router:
      default-provider / default-model / fallback-chain / task-routing
  loop:
    max-rounds: 3
    decision-timeout-minutes: 10
  rag.base-url: http://localhost:8000
  admin.base-url: http://localhost:8080
  sse:
    timeout-ms: 3600000
    heartbeat-ms: 25000
```

---

## 七、关键设计决策

- **不用 Spring AI**: 0.8.1 只支持单 ChatModel，多 Provider 需求不匹配
- **REPAY vs REPAYMENT**: AI 侧用 REPAY，ConfigWriter 写入时转 REPAYMENT
- **自动重试**: 有余量时不进决策点直接重试
- **嵌套 JSON**: fundField 点号路径 → buildFreeMarker 还原嵌套结构
- **allEmpty**: 数组子字段全为空占位符 → 输出 `[]` 不输出 `<#list>`

---

## 八、待办

| 优先级 | 项目 |
|--------|------|
| P1 | LoopState 持久化(断点恢复) |
| P1 | Prompt 规则外置 |
| P1 | 智能重试(confidence > 0.9 自动) |
| P2 | `<#list>` 内 item 字段自动映射 |
| P2 | 多接口批量解析 |
| P3 | RAG 知识整理(定期汇总 → LLM → 审核 → 入库) |

---

## 九、启动

```bash
# 1. MySQL + Qdrant
# 2. RAG Service
cd D:\xyFund\rag-system && python api.py
# 3. FundLink
java -jar fundlink-app/target/fundlink-app-1.0.0.jar
# 4. AI 后端 (IDEA Run FundLinkAiApplication)
# 5. 前端
cd D:\xyFund\fundlink\fundlink-ui && npm run dev
```

环境变量: `MYSQL_USER`, `MYSQL_PASSWORD`, `SILICONFLOW_API_KEY`, `QWEN_API_KEY`, `DEEPSEEK_API_KEY`
