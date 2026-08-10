# 00 — 总体概览

> 多接口分割 + 通用意图路由 设计计划
> 2026-08-10

---

## 背景

当前 FundLink AI 的 Copilot 是"单接口文档 → 单套配置"流水线。资金方文档通常包含多个接口（如放款申请 + 放款查询 + 还款申请），需要先拆分再逐个生成。同时前端输入框应该是通用的，后端需先识别意图再路由。

## 核心原则

1. **向后兼容**：单接口文档仍然正常工作，拆分出 1 个接口时退化为现有逻辑
2. **程序化优先**：拆分以正则/结构匹配为主，LLM 仅做校验和补漏
3. **子 Agent 并行**：每个接口独立 Prompt、独立 LLM 调用、独立写入
4. **兜底优先**：每一步都有降级路径，任何时候都可以退化为单接口处理
5. **去重内置**：拆分后端点去重，避免重复生成

## 总体架构

```
用户输入（任意内容）
  │
  ▼
┌──────────────────────────────────────┐
│ IntentRouter (意图识别) — 轻量 LLM    │
│  → INTERFACE_DEV / KNOWLEDGE_QA /    │
│    TROUBLESHOOTING                   │
└────────────────┬─────────────────────┘
                 │
    ┌────────────┼────────────┐
    ▼            ▼            ▼
 接口开发     知识问答     问题排查
 (已实现)    (新增架子)   (新增架子)
    │
    ▼
┌──────────────────────────────────────┐
│ DocumentSplitter (接口拆分)            │
│  Step 1: 程序化解析（正则/结构匹配）    │
│  Step 2: LLM 校验 + 补漏              │
│  Step 3: 去重 + 排序                  │
│  → List<InterfaceSegment>            │
└────────────────┬─────────────────────┘
                 │
    ┌────────────┼────────────┐
    ▼            ▼            ▼
 Interface-1  Interface-2  Interface-3    ← 并行子 Agent 处理
 (独立Prompt) (独立Prompt) (独立Prompt)
    │            │            │
    ▼            ▼            ▼
┌──────────────────────────────────────┐
│ ResultAggregator (结果聚合)            │
│  → MultiInterfaceResult              │
└──────────────────────────────────────┘
```

## 文档导航

| 序号 | 文档 | 内容 |
|------|------|------|
| 01 | [IntentRouter](./01-INTENT-ROUTER.md) | 意图识别：快速规则 + LLM + 策略路由 |
| 02 | [DocumentSplitter](./02-DOCUMENT-SPLITTER.md) | 程序化拆分策略链 + 去重 |
| 03 | [ParallelAgent](./03-PARALLEL-AGENT.md) | 并行子 Agent 处理 + Prompt 隔离 |
| 04 | [EdgeCases](./04-EDGE-CASES.md) | 10 个边缘场景与兜底方案 |
| 05 | [API Design](./05-API-DESIGN.md) | 后端 API + SSE 事件设计 |
| 06 | [Frontend Design](./06-FRONTEND-DESIGN.md) | 前端交互设计 |
| 07 | [File Changes](./07-FILE-CHANGES.md) | 文件变更清单 |
| 08 | [Implementation Plan](./08-IMPLEMENTATION-PLAN.md) | 实施步骤 + 验证方式 |
