# 05 — 后端 API 设计 + SSE 事件

> 优先级: P1（前后端契约，需尽早确定）
> 依赖: 01-IntentRouter, 02-DocumentSplitter

---

## 1. 新增 API

| 方法 | 路径 | 说明 | 优先级 |
|------|------|------|--------|
| POST | `/api/ai/intent` | 意图识别 | P0 |
| POST | `/api/ai/split` | 文档拆分 + 去重 | P0 |
| POST | `/api/ai/qa` | 知识问答（架子） | P2 |
| POST | `/api/ai/troubleshoot` | 问题排查（架子） | P2 |
| POST | `/api/ai/interfaces/{id}/retry` | 单接口重试 | P1 |

## 2. 修改 API

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/ai/analyze` | 自动 split → 并行处理 → 聚合返回 |
| POST | `/api/ai/loop` | 新增 `interfaceIds` 字段 |
| POST | `/api/ai/apply` | 新增 `interfaceId` 字段 |

## 3. Split API

```
POST /api/ai/split
Request:  { "documentText": "...", "providerCode": "CMB" }
Response: {
  "code": 0,
  "data": {
    "totalCount": 3,
    "interfaces": [
      {
        "interfaceId": "loanApply_a1b2c3",
        "interfaceName": "放款申请",
        "endpoint": "POST /api/loan/apply",
        "method": "POST",
        "flowType": "LOAN",
        "splitConfidence": 0.95,
        "splitSource": "MARKDOWN_HEADING",
        "sectionPreview": "## 1. 放款申请接口\n\n### 请求参数\n..."
      }
    ],
    "deduplications": [
      { "kept": "放款申请", "removed": "放款申请接口", "reason": "端点重复" }
    ],
    "warnings": [
      { "message": "接口 '放款申请' 和 '放款复核' 名称相似，请确认" }
    ]
  }
}
```

## 4. Analyze API（改造后）

```
POST /api/ai/analyze
Request:  {
  "documentText": "...",
  "providerCode": "CMB",
  "flowType": "",
  "selectedInterfaceIds": ["loanApply_a1b2c3"]  // 可选，不传=全部
}
Response: {
  "code": 0,
  "data": {
    "providerCode": "CMB",
    "totalCount": 3,
    "successCount": 2,
    "failedCount": 1,
    "interfaces": [
      {
        "interfaceId": "loanApply_a1b2c3",
        "interfaceName": "放款申请",
        "status": "SUCCESS",
        "result": { /* RequirementResult */ }
      },
      {
        "interfaceId": "loanQuery_b2c3d4",
        "interfaceName": "放款查询",
        "status": "FAILED",
        "errorMessage": "LLM 解析失败: 缺少接口字段定义"
      }
    ]
  }
}
```

## 5. SSE 事件扩展

### 原有事件（保持不变）

`phase:start`、`phase:progress`、`phase:complete`、`phase:error`、`decision_required`、`task:complete`、`task:failed`、`ping`

### 新增全局事件

| 事件名 | 数据 | 说明 |
|--------|------|------|
| `intent:result` | `{intent, confidence}` | 意图识别结果 |
| `split:start` | `{}` | 开始拆分 |
| `split:complete` | `{totalCount, interfaces: [{id, name, endpoint}]}` | 拆分完成 |
| `split:dedup` | `{kept, removed, reason}` | 去重报告 |
| `all:complete` | `{totalCount, successCount, failedCount}` | 全部接口完成 |

### 新增接口级事件（带 `interfaceId`）

| 事件名 | 数据 |
|--------|------|
| `interface:start` | `{interfaceId, name, index, total}` |
| `interface:phase:start` | `{interfaceId, phase, round, maxRounds}` |
| `interface:phase:progress` | `{interfaceId, phase, message}` |
| `interface:phase:complete` | `{interfaceId, phase, summary}` |
| `interface:phase:error` | `{interfaceId, phase, message}` |
| `interface:complete` | `{interfaceId, name, status, summary}` |
| `interface:skipped` | `{interfaceId, name, reason}` |

### 事件流示例（3 个接口并行）

```
split:start
split:complete     → { totalCount: 3, interfaces: [...] }
interface:start    → { interfaceId: "loanApply", name: "放款申请", index: 0, total: 3 }
interface:start    → { interfaceId: "loanQuery", name: "放款查询", index: 1, total: 3 }
interface:start    → { interfaceId: "repayApply", name: "还款申请", index: 2, total: 3 }

// 三个接口并行，事件交错推送
interface:phase:start  → { interfaceId: "loanApply", phase: "ANALYZE" }
interface:phase:start  → { interfaceId: "loanQuery", phase: "ANALYZE" }

interface:phase:complete → { interfaceId: "loanQuery", phase: "ANALYZE" }
interface:complete → { interfaceId: "loanQuery", status: "SUCCESS" }

interface:phase:error → { interfaceId: "repayApply", phase: "ANALYZE" }
interface:complete → { interfaceId: "repayApply", status: "FAILED" }

interface:phase:complete → { interfaceId: "loanApply", phase: "ANALYZE" }
interface:complete → { interfaceId: "loanApply", status: "SUCCESS" }

all:complete       → { totalCount: 3, successCount: 2, failedCount: 1 }
```

**前端根据 `interfaceId` 过滤事件，路由到对应接口卡片。**

### 向后兼容

单接口场景下 `interfaceId` 为 `null`，前端据此判断使用旧 UI（单面板）还是新 UI（多卡片）。
