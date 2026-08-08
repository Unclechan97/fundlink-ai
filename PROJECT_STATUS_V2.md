# FundLink AI — Project Status V2

> 2026-08-08 | 基于 AGENT_LOOP_DESIGN.md 闭环实施完成

---

## 一、闭环已实现

```
用户贴接口文档 → ANALYZE → WRITE → VALIDATE → DRYRUN → DECISION → PUBLISH
                        ↑         ↓          ↓          │
                        │     DIAGNOSE ←────┘      RETRY / ABORT
                        └────────────────────────────┘
```

### 驱动原理

```
AgentLoopOrchestrator.runLoop():
  while (phase != PUBLISHED && phase != FAILED) {
      switch (phase) {
          ANALYZE   → LLM 解析 → ConfigWriter 写入 → phase = VALIDATE
          VALIDATE  → TestGen 生成数据 → Preview 验证 → phase = DRYRUN
          DRYRUN    → Mock 注入 → executeSync 干跑 → phase = DECISION_POINT
          DIAGNOSE  → 规则 + LLM 诊断 → phase = DECISION_POINT
          DECISION  → CompletableFuture 阻塞等人 → phase = ANALYZE/PUBLISH/FAILED
          PUBLISH   → 调 publish API → phase = PUBLISHED
      }
  }
```

- 没有工作流框架，就是 **while + switch + 手动设 phase**
- 决策点用 `ConcurrentHashMap<taskId, CompletableFuture>` 阻塞，前端 POST `/decide` 唤醒
- SSE 推事件给前端，`SseEmitter.send()` 7 种事件类型

---

## 二、文件清单

### 新增 (16 files)

```
fundlink-ai-core/src/main/java/com/fundlink/ai/
├── config/
│   └── StartupDiagnostic.java          # 启动诊断面板
├── entity/
│   ├── AiTask.java                     # 任务实体
│   └── AiAgentTrace.java               # 轨迹实体
├── mapper/
│   ├── AiTaskMapper.java
│   └── AiAgentTraceMapper.java
├── gateway/
│   ├── RagGateway.java                 # RAG HTTP 统一封装
│   └── provider/
│       └── DeepSeekProvider.java       # DeepSeek Provider
└── agent/loop/
    ├── AgentLoopOrchestrator.java      # ★ 状态机引擎
    ├── LoopEventPublisher.java         # SSE 事件接口
    ├── LoggingLoopEventPublisher.java  # 日志实现
    ├── TaskPhase.java                  # 阶段枚举
    ├── DecisionRequest.java            # 决策 DTO
    ├── TemplateValidator.java          # Preview 验证
    ├── FlowDryRunner.java              # Mock + dry-run
    └── LoopTracer.java                 # 轨迹 + RAG 回写

fundlink-ai-app/src/main/java/com/fundlink/ai/controller/
├── LoopController.java                 # POST /loop, GET /stream, POST /decide
└── SseLoopEventPublisher.java         # SseEmitter 实现
```

### 重写 (8 files)

| 文件 | 改动 |
|------|------|
| `RequirementAgent.java` | `analyze()` 加 `previousErrors` 参数 |
| `RequirementAgentImpl.java` | 修正轮次注入诊断 Prompt |
| `TestGenAgent.java` | 新签名 `generate(FlowDsl, mappings, providerCode)` |
| `TestGenAgentImpl.java` | 真正调 LLM + parseSafely 解析 JSON |
| `TestGenResult.java` | 加 `previewData` 字段 |
| `TestCase.java` | 改为 targetBranch / inputData / mockRules |
| `DiagnosisAgent.java` | `diagnose(phase, error, context)` |
| `DiagnosisAgentImpl.java` | 规则引擎增强 + LLM JSON 解析 |
| `DiagnosisResult.java` | 加 correctedConfig / phase |

### 修改 (6 files)

| 文件 | 改动 |
|------|------|
| `LlmGatewayImpl.java` | SmartRouter + fallback chain |
| `LlmRequest.java` | 加 getTaskType() + ofTask() |
| `SmartRouter.java` | 全路由到 Qwen (体验额度) |
| `ConfigWriter.java` | enrichFlowDsl 修复 templateCode |
| `application.yml` | default-provider: qwen |
| `schema-ai.sql` ×2 | ai_task / ai_agent_trace 新字段 |

### FundLink 后端

| 文件 | 改动 |
|------|------|
| `UpstreamController.java` | `POST /api/upstream/flows/{id}/dry-run` |

---

## 三、API 端点

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/ai/analyze` | 手动-解析文档 |
| POST | `/api/ai/apply` | 手动-写入配置 |
| POST | `/api/ai/loop` | 自动-创建闭环任务 |
| GET | `/api/ai/loop/{id}/stream` | 自动-SSE 进度流 |
| POST | `/api/ai/loop/{id}/decide` | 自动-发送决策 |
| GET | `/api/ai/loop/{id}` | 自动-任务状态 |
| POST | `/api/upstream/flows/{id}/dry-run` | FundLink 干跑 |

### SSE 事件

```
phase:start        {phase, round, maxRounds}
phase:progress     {phase, message}
phase:complete     {phase, summary}
phase:error        {phase, message}
decision_required  {type, summary, options}
task:complete      {status, summary}
task:failed        {status, error, rounds}
```

---

## 四、数据库

### ai_task 新增

| 字段 | 用途 |
|------|------|
| `current_round` | 当前重试轮次 |
| `max_rounds` | 最大轮次 (默认3) |
| `flow_type` | LOAN/CREDIT/REPAY |
| `provider_code` | 资金方编码 |
| `document_text` | 输入文档原文 |
| `current_result` | 当前轮结果 JSON 快照 |
| `update_time` | 更新时间 |

### ai_agent_trace 新增

| 字段 | 用途 |
|------|------|
| `phase` | ANALYZE/VALIDATE/DRYRUN/DIAGNOSE |
| `agent_type` | requirement/testgen/diagnosis |
| `input_summary` | 输入摘要 |
| `output_summary` | 输出摘要 |
| `duration_ms` | 执行耗时 |
| `success` | 是否成功 |

---

## 五、当前配置

```
LLM Router:    qwen (all tasks)
Fallback:      qwen → deepseek
FundLink:      localhost:8080
RAG:           localhost:8000
Qdrant:        localhost:6333
MySQL:         localhost:3306
```

---

## 六、待完成

### P0 — 前端自动模式 (设计 §10)

```
fundlink-ui 新增:
├── AutoLoopPanel.jsx       # SSE 进度面板
│   ├── 进度条 (ANALYZE → VALIDATE → DRYRUN → PUBLISH)
│   ├── 轮次显示 (Round 1/3)
│   ├── 阶段输出 (可折叠)
│   └── 决策按钮组 (RETRY / SKIP / ABORT / PUBLISH)
└── Copilot.jsx 改动        # [手动/自动] 开关
```

前端 SSE 消费示例：
```javascript
const es = new EventSource(`/api/ai/loop/${taskId}/stream`);
es.addEventListener('phase:start',    e => updateProgress(JSON.parse(e.data)));
es.addEventListener('phase:complete', e => appendOutput(JSON.parse(e.data)));
es.addEventListener('phase:error',    e => showError(JSON.parse(e.data)));
es.addEventListener('decision_required', e => showButtons(JSON.parse(e.data)));
es.addEventListener('task:complete',  e => onDone(JSON.parse(e.data)));
```

### P1 — 质量加固

| 项目 | 说明 |
|------|------|
| `AgentLoopOrchestratorTest` | 单元测试 — mock 全部依赖，验证状态转换 |
| `TemplateValidatorTest` | Preview API mock 测试 |
| `FlowDryRunnerTest` | dry-run mock 测试 |
| `RagGateway` 替换 | PromptEnhancer / KnowledgeAutoWriter 改用 RagGateway |
| `EDIT_AND_RETRY` | 前端传回人工修改的配置，注入下一轮 |

### P2 — 功能增强

| 项目 | 说明 |
|------|------|
| 多接口批量解析 | 一次文档含 loan/credit/repay，按 flowType 分发 |
| Claude Haiku Provider | 诊断任务用 Claude，精确度更高 |
| SmartRouter 自动切换 | DeepSeek 余额够自动切过去，不够切 Qwen |
| 决策超时自动处理 | 10 分钟无人响应 → 自动标记 FAILED + 通知 |

---

## 七、启动命令

```bash
# FundLink 后端 (8080)
cd D:\xyFund\fundlink\fundlink
java -jar fundlink-app/target/fundlink-app-1.0.0.jar

# AI 后端 (8081) — IDEA Run Configuration
Main class: com.fundlink.ai.FundLinkAiApplication
Env: MYSQL_PASSWORD=xxx QWEN_API_KEY=xxx DEEPSEEK_API_KEY=xxx

# RAG (8000)
cd D:\xyFund\rag-system && python api.py
```

---

## 八、测试

```bash
# Postman
导入: fundlink-ai-loop.postman_collection.json

# curl
curl -X POST http://localhost:8081/api/ai/loop \
  -H "Content-Type: application/json" \
  -d '{"documentText":"...接口文档...","providerCode":"TEST","flowType":"LOAN"}'

curl -N http://localhost:8081/api/ai/loop/1/stream

# dry-run 直接测
curl -X POST http://localhost:8080/api/upstream/flows/19/dry-run \
  -H "Content-Type: application/json" \
  -d '{"inputData":{...}}'
```
