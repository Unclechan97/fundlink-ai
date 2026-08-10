# 07 — 文件变更清单

> 优先级: 参考文档
> 汇总所有需要新增和修改的文件

---

## 新增文件（18 个）

### intent/ 包（8 个）

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/intent/
├── IntentRouter.java              # 意图路由（快速规则 + LLM）
├── IntentType.java                # 意图枚举
├── IntentResult.java              # 识别结果
├── IntentHandler.java             # 策略接口
├── IntentContext.java             # 上下文对象
├── InterfaceDevHandler.java       # 接口开发处理器
├── KnowledgeQaHandler.java        # 知识问答处理器（架子）
└── TroubleshootingHandler.java    # 问题排查处理器（架子）
```

### split/ 包（8 个）

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/split/
├── DocumentSplitter.java          # 拆分器主类（策略链编排）
├── SplitStrategy.java             # 拆分策略接口
├── MarkdownHeadingStrategy.java   # 策略1：Markdown 标题匹配
├── DelimiterStrategy.java         # 策略2：分隔线匹配
├── AnchorStrategy.java            # 策略3：端点锚点匹配
├── LlmVerifyStrategy.java         # LLM 校验 + 补漏
├── InterfaceSegment.java          # 接口片段模型
└── InterfaceDeduplicator.java     # 去重器
```

### requirement/ 包（1 个）

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/requirement/
└── MultiInterfaceResult.java      # 多接口结果聚合
```

### Controller（无新增，在现有 Controller 中加方法）

知识问答和问题排查的 API 端点加在 `CopilotController.java` 中，不单独建 Controller。

---

## 修改文件（12 个）

### fundlink-ai-core（7 个）

```
RequirementAgentImpl.java    # 无接口变更（接口片段由上层传入，analyze() 签名不变）
RequirementResult.java       # 新增: interfaceId, interfaceName, interfaceIndex, totalInterfaces
ConfigWriter.java            # writeAll() 新增 interfaceId 参数；Template/Flow code 带 interfaceId
AgentLoopOrchestrator.java   # 新增 SPLITTING/PROCESSING_INTERFACES 阶段；并行接口处理
PromptBuilder.java           # 新增: buildSplitPrompt(), buildIntentPrompt(), buildInterfacePrompt()
LoopTracer.java              # trace() 新增 interfaceId 参数
LoopEventPublisher.java      # 新增: split/interface 事件方法
```

### fundlink-ai-app（3 个）

```
CopilotController.java       # 新增: /split, /intent, /qa, /troubleshoot 端点
                             # 修改: /analyze 返回 MultiInterfaceResult
LoopController.java          # 修改: /loop 支持 interfaceIds 参数
SseLoopEventPublisher.java   # 新增: 多接口事件类型 (split/interface 系列)
```

### fundlink-ui（2 个）

```
src/pages/ai/Copilot.jsx       # 通用输入 + 意图展示 + 多接口列表 + Collapse 面板
src/pages/ai/AutoLoopPanel.jsx # 多接口卡片式进度 + 新 SSE 事件处理
src/api/index.js               # 新增: splitDocument(), getIntent(), qa(), troubleshoot()
```

---

## 不受影响的文件

以下文件不需要修改：

- `RequirementAgent.java` / `RequirementAgentImpl.java` — 接口签名和核心逻辑不变
- `FlowTypeDetector.java` — 仍按单接口文档检测，由上层在拆分后传入
- `FieldCompletenessGuard.java` — 不变
- `DiagnosisAgent.java` / `DiagnosisAgentImpl.java` — 不变
- `TestGenAgent.java` / `TestGenAgentImpl.java` — 不变
- `TemplateValidator.java` — 不变
- `FlowDryRunner.java` — 不变
- `LlmGateway.java` / `LlmGatewayImpl.java` — 不变
- `SmartRouter.java` — 不变
- `RagGateway.java` — 不变
- 所有 Mapper / Entity — 不变
