# 08 — 实施步骤 + 验证方式

> 优先级: 实施指南
> 5 个 Phase，按依赖关系排序

---

## Phase 1：拆分器 + 去重（P0，纯后端，无侵入）

**目标**：程序化拆分可用，不影响现有流程

1. 新增 `SplitStrategy` 接口 + 4 个实现
2. 新增 `InterfaceSegment`、`InterfaceDeduplicator`
3. 实现 `DocumentSplitter`（策略链编排 + LLM 校验）
4. 单测：用多个格式的文档验证拆分结果

**验证**：
- 3 个 `## 接口` 标题的文档 → 拆出 3 个
- `---` 分隔的 5 个接口 → 拆出 5 个
- 无结构纯文本 → 拆出 1 个（兜底）
- 重复接口 → 去重后只保留 1 个

---

## Phase 2：意图路由（P0，后端基础）

**目标**：输入任意内容能正确路由

1. `IntentRouter` + 快速规则 + LLM 识别
2. `InterfaceDevHandler` 委托现有逻辑
3. `KnowledgeQaHandler` / `TroubleshootingHandler` 架子
4. `CopilotController` 新增 `/intent`、`/split`、`/qa`、`/troubleshoot` 端点

**验证**：
- 贴入接口文档 → 识别为 `INTERFACE_DEV`（快速规则命中，无 LLM 调用）
- 贴入 `Exception in thread ...` → 识别为 `TROUBLESHOOTING`
- 输入"什么是放款流程？" → 识别为 `KNOWLEDGE_QA`
- 低置信度内容 → 返回 `needUserConfirm: true`

---

## Phase 3：并行处理引擎（P1，后端核心）

**目标**：多接口并行 ANALYZE + 独立写入

1. `MultiInterfaceResult` 模型
2. `RequirementResult` 新增 `interfaceId` 等字段
3. `ConfigWriter.writeAll()` 加 `interfaceId` 参数
4. `AgentLoopOrchestrator` 新增 `SPLITTING` / `PROCESSING_INTERFACES` 阶段
5. `PromptBuilder` 新增 `buildInterfacePrompt()`
6. `SseLoopEventPublisher` 新增多接口事件

**验证**：
- 3 个接口并行 ANALYZE → 总耗时 ≈ 最慢一个，而非 3 倍
- 4 个接口中 1 个 LLM 返回空 → 3 成功 + 1 失败，不相互影响
- 单接口文档 → LoopState 无 interfaceId，走现有逻辑

---

## Phase 4：前端改造（P1，与 Phase 3 并行）

**目标**：通用输入 + 多接口展示可用

1. Copilot.jsx：通用输入框 + 意图结果展示 + 接口列表勾选 + Collapse 面板
2. AutoLoopPanel.jsx：多接口卡片式进度 + SSE 按 `interfaceId` 路由
3. api/index.js：新增 API 调用
4. 知识问答 / 问题排查基础 UI（架子）

**验证**：
- 粘贴文档 → 意图识别展示 → 接口列表 → 勾选 → 手动逐个解析
- 粘贴文档 → 自动闭环 → 多接口卡片实时更新进度
- 知识问答 → 对话式 UI 可用
- 意图误判 → 一键切换 → 进入正确流程

---

## Phase 5：集成测试（P1）

**目标**：端到端覆盖所有场景

| 场景 | 输入 | 预期 |
|------|------|------|
| 单接口回归 | 现有单接口文档 | 行为与改造前一致 |
| 多接口 Markdown | 含 3 个 `## 接口` 标题 | 拆出 3 个，并行成功 |
| 多接口分隔线 | `---` 分隔 5 个接口 | 拆出 5 个，分两批并行 |
| 重复接口 | 同一接口出现两次 | 去重保留内容更丰富的 |
| 非标准格式 | 无标题无分隔线纯文本 | 兜底为单接口 |
| 部分失败 | 3 个中 1 个 LLM 返回空 | 2 成功 + 1 失败，可重试 |
| 意图误判 | 接口文档被识别为 QA | 用户手动切换为接口开发 |
| 超大文档 | 25 个接口 | 前端提示数量，分批并行 |
| 中断恢复 | 处理中取消 | 已完成保留，未完成放弃 |
| 知识问答 | 输入"什么是放款流程" | LLM 直接回答 |

---

## 依赖关系图

```
Phase 1 (Splitter) ──┐
                      ├──→ Phase 3 (Parallel) ──→ Phase 5 (Test)
Phase 2 (Intent)  ────┘
                      │
Phase 4 (Frontend) ───┘ (可与 Phase 3 并行开发)
```

Phase 1 和 Phase 2 互不依赖，可并行开发。
Phase 3 依赖 Phase 1 的 `DocumentSplitter` 和 Phase 2 的 `IntentRouter`。
Phase 4 依赖 Phase 3 的 API 和 SSE 事件格式确定后即可开发（可并行）。
Phase 5 依赖全部 Phase 完成。
