# 01 — 意图路由（IntentRouter）

> 优先级: P0（基础设施，其他模块依赖）
> 依赖: LlmGateway（已有）

---

## 1. 目标

用户粘贴任意内容到输入框后，后端自动判断意图并路由到对应处理器：

- **INTERFACE_DEV**：接口文档 → 进入拆分 + 生成流程（已有实现）
- **KNOWLEDGE_QA**：业务问题 → LLM 直接回答（新增架子）
- **TROUBLESHOOTING**：报错日志 → LLM 分析诊断（新增架子）

## 2. 设计决策：快速规则优先 + LLM 兜底

不需要每次都调 LLM 做意图识别。先用强特征做快速规则预判，命中则跳过 LLM；规则覆盖不到时才调 LLM。

```
IntentRouter.route(userInput)
  │
  ├─ Step 1: quickRuleCheck() → 命中 → 直接返回（0 cost）
  │
  └─ Step 2: LLM intent prompt → 返回 IntentResult
```

## 3. 快速规则

```java
private IntentType quickRuleCheck(String input) {
    // === 问题排查：堆栈跟踪特征 ===
    if (input.contains("Exception") || input.contains("at com.")
        || input.contains("Caused by:") || input.contains("Stack trace:")) {
        return IntentType.TROUBLESHOOTING;
    }

    // === 接口文档：HTTP 方法特征 ===
    if (input.matches("(?s).*(?:POST|GET|PUT|DELETE)\\s+/[a-zA-Z].*")) {
        return IntentType.INTERFACE_DEV;
    }

    // === 接口文档：参数表格特征 ===
    if (input.contains("请求参数") || input.contains("响应参数")
        || input.contains("接口名称") || input.contains("字段名")
        || input.contains("入参") || input.contains("出参")) {
        return IntentType.INTERFACE_DEV;
    }

    // === 知识问答：疑问句特征 ===
    if (input.contains("?") || input.contains("？")
        || input.startsWith("什么是") || input.startsWith("如何")
        || input.contains("怎么")) {
        return IntentType.KNOWLEDGE_QA;
    }

    return null; // 无强特征 → 走 LLM
}
```

## 4. LLM 意图识别 Prompt

仅当快速规则无法判定时调用：

```
分析用户输入，判断意图类型：
{
  "intent": "INTERFACE_DEV|KNOWLEDGE_QA|TROUBLESHOOTING",
  "confidence": 0.0-1.0,
  "reason": "简短理由"
}

判断规则：
- INTERFACE_DEV: 包含 API 端点/接口字段/入参出参/接口规范
- KNOWLEDGE_QA: 询问业务知识/产品规则/流程说明
- TROUBLESHOOTING: 包含错误日志/异常堆栈/报错描述
```

## 5. 模型定义

### IntentType

```java
public enum IntentType {
    INTERFACE_DEV("接口开发"),
    KNOWLEDGE_QA("知识问答"),
    TROUBLESHOOTING("问题排查"),
    UNKNOWN("未知");
}
```

### IntentResult

```java
public class IntentResult {
    IntentType intentType;
    double confidence;        // 0.0-1.0
    String reason;            // 判定理由
    Map<String, Object> extractedInfo; // 提取的关键信息
}
```

### IntentHandler（策略接口）

```java
public interface IntentHandler {
    IntentType supportedType();
    Object handle(IntentContext ctx);
}
```

## 6. Handler 实现

### InterfaceDevHandler

```java
@Component
public class InterfaceDevHandler implements IntentHandler {
    public IntentType supportedType() { return IntentType.INTERFACE_DEV; }

    public Object handle(IntentContext ctx) {
        // 委托给现有的 DocumentSplitter + RequirementAgent 流程
        // 这是已有逻辑的入口适配
    }
}
```

### KnowledgeQaHandler（架子）

```java
@Component
public class KnowledgeQaHandler implements IntentHandler {
    public IntentType supportedType() { return IntentType.KNOWLEDGE_QA; }

    public Object handle(IntentContext ctx) {
        String prompt = "你是资金接入系统专家。回答用户问题：\n" + ctx.getUserInput();
        LlmResponse resp = llmGateway.chat(LlmRequest.ofTask("qa", prompt, ctx.getTraceId()));
        return QaResult.of(resp.getContent());
    }
}
```

### TroubleshootingHandler（架子）

```java
@Component
public class TroubleshootingHandler implements IntentHandler {
    public IntentType supportedType() { return IntentType.TROUBLESHOOTING; }

    public Object handle(IntentContext ctx) {
        String prompt = "你是资金接入系统运维专家。分析以下错误：\n" + ctx.getUserInput();
        LlmResponse resp = llmGateway.chat(LlmRequest.ofTask("troubleshoot", prompt, ctx.getTraceId()));
        return TroubleshootResult.of(resp.getContent());
    }
}
```

## 7. 兜底：低置信度时前端确认

```java
public IntentResult route(String userInput, Map<String, Object> context) {
    IntentType quick = quickRuleCheck(userInput);
    if (quick != null && quick != IntentType.UNKNOWN) {
        return IntentResult.of(quick, 0.95, "规则匹配");
    }

    IntentResult llmResult = llmIntentRecognition(userInput);

    // 低置信度 → 标记需要用户确认
    if (llmResult.getConfidence() < 0.7) {
        llmResult.setNeedUserConfirm(true);
    }

    return llmResult;
}
```

前端收到 `needUserConfirm: true` 时弹确认框让用户手动选择意图。

## 8. 新增文件

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/intent/
├── IntentRouter.java
├── IntentType.java
├── IntentResult.java
├── IntentHandler.java
├── IntentContext.java
├── InterfaceDevHandler.java
├── KnowledgeQaHandler.java
└── TroubleshootingHandler.java
```
