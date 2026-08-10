# 多接口自动闭环 — 重构计划

## Context

当前手动模式下多接口功能与单接口功能混在一起，交互混乱（checkbox 误触、结果不持久化、进度不展示）。用户要求：

1. **手动模式** → 纯单接口调试，去掉所有多接口相关代码
2. **自动模式** → 点击"AI 解析" → 拆分 → 用户确认接口列表 → 勾选 → "确认执行" → 并行闭环 → 下方展示所有接口进度卡片
3. **持久化** → ai_task 表支持主任务 + 子任务

## 架构概览

```
手动模式（不变）                    自动模式（重构）
┌──────────────┐              ┌──────────────────────────┐
│ 粘贴文档      │              │ 粘贴文档                   │
│ [AI 解析]     │              │ [AI 解析]                  │
│   ↓          │              │   ↓                       │
│ analyze()    │              │ intent() → split()        │
│   全文→单接口 │              │   显示接口列表（勾选确认）   │
│   ↓          │              │   ↓                       │
│ 字段映射表    │              │ [确认执行]                  │
│ 流程图       │              │   ↓                       │
│ [写入配置]   │              │ POST /loop/multi           │
└──────────────┘              │   创建 1 父任务 + N 子任务  │
                              │   ↓                       │
                              │ N 个子任务并行闭环         │
                              │   每个: ANALYZE→VALIDATE   │
                              │        →DRYRUN→PUBLISH    │
                              │   ↓                       │
                              │ N 张进度卡片 + SSE 实时更新 │
                              └──────────────────────────┘
```

## 1. AiTask 表改动

```sql
ALTER TABLE ai_task ADD COLUMN parent_task_id BIGINT DEFAULT NULL;
ALTER TABLE ai_task ADD COLUMN interface_id VARCHAR(100) DEFAULT NULL;
ALTER TABLE ai_task ADD COLUMN interface_name VARCHAR(200) DEFAULT NULL;
```

**主任务记录**：`parent_task_id = NULL`，`interface_id = NULL`，`documentText = 全文`  
**子任务记录**：`parent_task_id = 主任务 ID`，`interface_id = segment.interfaceId`，`documentText = 该接口片段`

### interfaceId 重新设计

当前 `InterfaceSegment.interfaceId` 用 `interfaceName + hash` 生成，不可读且不稳定。改为从 endpoint 派生：

```
interfaceId = shortNameFromEndpoint(endpoint)
例如:
  POST /api/loan/apply  →  LOAN_APPLY
  POST /api/loan/query  →  LOAN_QUERY
  POST /api/repay/apply →  REPAY_APPLY
  POST /api/repay/query →  REPAY_QUERY
```

生成逻辑新增一个 `EndpointShortName` 工具方法，放在 `InterfaceSegment` 或独立工具类。

## 2. 后端 API

### 2.1 新增：POST `/api/ai/loop/multi`

```java
// Request
{
  "documentText": "...全文...",
  "providerCode": "DBS",
  "flowType": "",
  "selectedInterfaceIds": ["LOAN_APPLY", "LOAN_QUERY"],
  "maxRounds": 3
}

// Response
{
  "parentTaskId": 100,
  "parentTaskNo": "MULTI-ABC12345",
  "subTasks": [
    { "taskId": 101, "interfaceId": "LOAN_APPLY", "interfaceName": "放款申请" },
    { "taskId": 102, "interfaceId": "LOAN_QUERY", "interfaceName": "放款查询" }
  ]
}
```

### 2.2 新增：`MultiLoopOrchestrator`

不修改现有 `AgentLoopOrchestrator`，新建 `MultiLoopOrchestrator`：

```
createMultiLoop(documentText, providerCode, flowType, selectedInterfaceIds):
  1. DocumentSplitter.split(documentText) → N segments
  2. filter by selectedInterfaceIds → M segments
  3. 创建父任务 (parent_task_id=null)
  4. 对每个 segment:
     a. 创建子任务 (parent_task_id=父任务ID, interface_id=..., documentText=sectionText)
     b. 从 AgentLoopOrchestrator 复用 start(taskId) 逻辑
  5. 返回父任务ID + 子任务列表
```

关键点：复用现有的 `AgentLoopOrchestrator.start(taskId)` — 子任务进入已有闭环流程。每个子任务的 `LoopState.documentText` 设置为该接口的 `sectionText`（通过 `buildInterfacePrompt` 构建）。

### 2.3 LoopController 改动

新增 `createMulti` 端点。子任务创建后自动 `orchestrator.start(subTaskId)`。

### 2.4 CopilotController 改动

- `/analyze` 回退：删除 `selectedInterfaceIds` 分支，只保留单接口逻辑
- `/split` 保留不变（自动模式拆分用）
- `/intent` 保留不变

## 3. 前端改造

### 3.1 Copilot.jsx

**手动模式**：回退到原始单接口版本。删除：
- `intent`, `splitInterfaces`, `selectedIds`, `multiResults` 等 state
- `handleSend` 中的多接口逻辑
- 接口列表卡片
- `handleSwitchInterface` 等函数

保留：`handleAnalyze`、字段映射表、流程图、写入按钮。

**自动模式**：

新增 state（仅自动模式）：
```javascript
const [autoStep, setAutoStep] = useState('input'); // 'input' | 'confirm' | 'running'
const [splitInterfaces, setSplitInterfaces] = useState([]);
const [selectedIds, setSelectedIds] = useState([]);
const [multiTask, setMultiTask] = useState(null); // { parentTaskId, subTasks: [...] }
```

流程：
1. 用户粘贴文档 → 点击 "AI 解析"
2. → `POST /api/ai/intent` + `POST /api/ai/split` → 显示接口列表
3. → `setAutoStep('confirm')` → 用户勾选 → 点击 "确认执行"
4. → `POST /api/ai/loop/multi` → 获得 parentTaskId + subTasks
5. → `setAutoStep('running')` → 渲染 `MultiTaskProgress` 组件

### 3.2 新增：MultiTaskProgress 组件

替代 AutoLoopPanel 用于多接口场景：

```jsx
function MultiTaskProgress({ parentTaskId, subTasks, providerCode }) {
  // 每个子任务建立独立 SSE 连接
  // 渲染 N 张进度卡片
  return (
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
      {subTasks.map(task => (
        <TaskCard key={task.taskId} taskId={task.taskId} 
          interfaceName={task.interfaceName} interfaceId={task.interfaceId} />
      ))}
    </div>
  );
}
```

### 3.3 TaskCard 组件（提取自 AutoLoopPanel）

从 AutoLoopPanel 提取核心逻辑为新组件 `TaskCard`，用于展示单个接口的闭环进度：
- Steps 进度条（ANALYZE → VALIDATE → DRYRUN → PUBLISH）
- 阶段日志 Collapse
- 决策面板（如果需要人工介入）
- 完成/失败状态

### 3.4 api/index.js 新增

```javascript
/** 创建多接口闭环任务 */
export const createMultiLoop = (documentText, providerCode, flowType, selectedInterfaceIds, maxRounds) =>
  aiApi.post('/api/ai/loop/multi', { documentText, providerCode, flowType, selectedInterfaceIds, maxRounds });
```

## 4. 分步实施

### Step 1：后端回退 + 重构
- `CopilotController.analyze()` — 删除 `selectedInterfaceIds` 分支
- `InterfaceSegment.interfaceId` — 改为从 endpoint 派生
- `ConfigWriter` 修复 — `enrichFlowDsl` 传入完整 templateCode（含 interfaceId）
- `/split` 端点 — 返回真实 `DedupResult` 数据
- 验证：72+ 测试通过

### Step 2：AiTask 表 + MultiLoopOrchestrator
- `AiTask` 实体加字段（parentTaskId, interfaceId, interfaceName）
- `MultiLoopOrchestrator` 实现
- `LoopController.createMulti()` 端点
- 验证：主任务 + 子任务创建正确，SSE 连接正常

### Step 3：前端回退手动模式
- Copilot.jsx 删除多接口 state 和 UI
- 手动模式回到原始单接口 UI
- 验证：手动模式功能正常

### Step 4：前端自动模式重构
- Copilot.jsx 自动模式新 UI（input → confirm → running）
- `MultiTaskProgress` + `TaskCard` 组件
- SSE 多连接管理
- 验证：自动模式端到端可用

## 5. 验证方式

| 测试 | 步骤 | 预期 |
|------|------|------|
| 手动模式回归 | 粘贴单接口文档 → AI 解析 | 字段映射表 + 流程图正常 |
| 自动-拆分 | 粘贴多接口文档 → AI 解析 | 意图识别 + 4 个接口列表 |
| 自动-确认 | 勾选 3 个 → 确认执行 | 创建 1 主 + 3 子任务 |
| 自动-进度 | 查看进度卡片 | 3 张卡片各自独立更新 |
| 自动-失败 | 1 个接口失败 | 其余 2 个继续，失败卡片显示错误 |
| 持久化 | 刷新页面 → 任务中心 | 主任务 + 子任务记录存在 |

## 6. 需要连带修复的已有 Bug

| Bug | 位置 | 修复 |
|-----|------|------|
| `ConfigWriter.getOrCreateFlow` → `enrichFlowDsl` 使用 `templateCode = "AI_" + code` 不含 interfaceId | ConfigWriter.java:393 | 传入完整 templateCode（含 interfaceId） |
| `/split` 端点 `deduplications` 和 `warnings` 硬编码空列表 | CopilotController.java:258-259 | 返回 `DedupResult` 真实数据 |
| `CopilotController.analyze()` 多接口分支 | 98-103 行 | 删除此分支 |
| `AutoLoopPanel` 的 `interface:*` SSE 监听器为空 | AutoLoopPanel.jsx:276-310 | 重构为 MultiTaskProgress 时填充 |

## 7. 不受影响的模块

- `DocumentSplitter` + 策略链 — 不变
- `IntentRouter` — 不变
- `RequirementAgent` — 不变
- `PromptBuilder.buildInterfacePrompt()` — 不变
- `AgentLoopOrchestrator` — 不变（子任务通过 `start(taskId)` 复用现有闭环）
- 现有 SSE 事件协议 — 不变（子任务复用现有 phase:* / decision_required / task:* 事件）
