# FundLink AI 项目功能清单

> 最后更新：2026-08-10 | 含 RAG 接入 & Tool Calling 排查体系

---

## 一、项目架构概览

```
D:\xyFund\fundlink-ai\          ← AI 后端 (Spring Boot :8081)
├── fundlink-ai-core/           ← Agent / Gateway / Tools 核心逻辑
└── fundlink-ai-app/            ← Web 层 (Controller + SSE)

D:\xyFund\fundlink\             ← FundLink 主项目
├── fundlink-admin/             ← 管理后台 API (Spring Boot :8080)
├── fundlink-api/               ← 上游接口网关 (Spring Boot :8080)
├── fundlink-flow/              ← DAG 流程引擎
├── fundlink-render/            ← FreeMarker 模板渲染
├── fundlink-mock/              ← Mock 数据服务
├── fundlink-repository/        ← 数据访问层 (MyBatis-Plus, fl_* 表)
├── fundlink-common/            ← 公共 DTO / 工具类
└── fundlink-ui/                ← React 前端 (Vite :3000)

D:\xyFund\rag-system\           ← RAG 知识库 (Python FastAPI :8000 + Qdrant)
MySQL: fundlink 库, ai_task + fl_* 表共存
```

---

## 二、前端页面 (React 18 + AntD + ReactFlow + Monaco)

| 路由 | 页面 | 功能 | 状态 |
|------|------|------|:--:|
| `/` | Dashboard | 统计卡片：Providers / Templates / Flows / Mock Rules 数量 | ✅ |
| `/providers` | Providers | 资金方 CRUD（provider_code, provider_name, base_url, mock_url, timeout_ms） | ✅ |
| `/templates` | Templates | 模板列表 + 翻页 + 删除 | ✅ |
| `/templates/new` `/templates/:id` | TemplateEdit | FreeMarker 模板编辑（Monaco）+ 字段映射 CRUD + 在线预览 | ✅ |
| `/flows` | Flows | 流程列表 + 翻页 + 发布/删除 | ✅ |
| `/flows/new` `/flows/:id` | FlowEdit | 可视化 DAG 编辑器（ReactFlow）：6 种节点类型 + 连线条件表达式 | ✅ |
| `/mock` | MockRules | Mock 规则 CRUD + 启停开关 + 在线调试 | ✅ |
| `/enums` | EnumMappings | 枚举映射 CRUD（enumType / internal / external / providerId） | ✅ |
| `/logs` | Logs | 流程实例 + API 日志（只读） | ✅ |
| `/ai/copilot` | AI Copilot | **AI 主页面** — 见下方详细说明 | ✅ |
| `/ai/tasks` | TaskCenter | 任务中心表格 + 进度条 | ⚠️ Mock 数据 |
| `/ai/trace` | AgentTrace | Agent 执行 Trace 时间线 | ⚠️ Mock 数据 |

### AI Copilot (`/ai/copilot`) 功能

**手动模式 (单接口调试)：**
1. 粘贴接口文档 + 输入资金方编码
2. 点击 "AI 解析" → `RequirementAgent` 分析 → 返回 `RequirementResult`
3. 字段映射表（可编辑、逐行采纳、一键采纳）
4. 流程 DSL 可视化（ReactFlow，可编辑节点/连线属性）
5. "写入 FundLink 配置" → 调用 `ConfigWriter` 写入 provider + template + mappings + flow

**自动模式 (多接口闭环)：**
1. 粘贴多接口文档 → "AI 解析"
2. 意图识别 (`IntentRouter`) + 文档拆分 (`DocumentSplitter`)
3. 接口列表确认（勾选 → "确认执行"）
4. `POST /api/ai/loop/multi` → 创建父任务 + N 个子任务
5. `MultiTaskProgress` 渲染 N 张 `TaskCard`，每张独立 SSE 连接
6. 每张卡片实时展示：Steps 进度（解析→验证→干跑→发布）+ 阶段日志 + 决策面板 + 完成/失败状态

**知识问答：** 直接输入问题 → RAG 检索知识库 → LLM 结合知识库内容回答

**问题排查：** 粘贴报错日志 → RAG 检索历史案例 → LLM 通过 Tool Calling 查询系统配置（模板/映射/流程）→ 精准诊断

---

## 三、AI 后端 API (Spring Boot :8081)

### 3.1 Copilot 接口 `/api/ai`

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/analyze` | 单接口文档 → AI 解析为配置 (flowType, providerConfig, interfaceSchema, fieldMappings, flowDsl) |
| POST | `/apply` | 审核通过后一键写入 FundLink |
| POST | `/suggest-mappings` | 简化字段映射建议 (fundField/sourcePath/transform/confidence) |
| POST | `/intent` | 意图识别：INTERFACE_DEV / KNOWLEDGE_QA / TROUBLESHOOTING + 置信度 |
| POST | `/split` | 多接口文档拆分 → 接口列表 + 去重报告 + 相似度告警 |
| POST | `/qa` | 业务知识问答（先 RAG 检索再 LLM 回答） |
| POST | `/troubleshoot` | 报错日志诊断（RAG + Tool Calling 多轮查询系统配置） |

### 3.2 闭环接口 `/api/ai/loop`

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/` | 创建单接口闭环任务 → `{taskId, taskNo}` |
| POST | `/multi` | 创建多接口闭环任务 → `{parentTaskId, parentTaskNo, subTasks}` |
| GET | `/{taskId}/stream` | SSE 事件流（注册 emitter + 启动 orchestrator） |
| POST | `/{taskId}/decide` | 人工决策：RETRY / SKIP / EDIT_AND_RETRY / PUBLISH / ABORT |
| POST | `/{taskId}/cancel` | 用户中断 |
| GET | `/{taskId}` | 任务状态查询 |
| GET | `/{taskId}/result` | 当前解析结果快照（供 EDIT_AND_RETRY 编辑） |

### 3.3 审计接口 `/api/ai/audit`

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/export?from&to` | 导出 LLM 审计日志 CSV |
| GET | `/cost?from&to` | 费用汇总 |

### 3.4 SSE 事件协议

| 事件名 | 触发时机 |
|--------|----------|
| `phase:start` | 阶段开始 (ANALYZE / VALIDATE / DRYRUN / PUBLISH) |
| `phase:progress` | 阶段内进度消息 |
| `phase:complete` | 阶段完成 + 摘要（含模板ID/流程ID/映射数） |
| `phase:error` | 阶段错误 |
| `decision_required` | 需要人工决策（含 type / summary / options） |
| `task:complete` | 闭环完成（含 模板#X / 流程#Y / N字段映射） |
| `task:failed` | 闭环失败 |
| `ping` | 心跳（25s 间隔） |

---

## 四、Agent 智能体模块

### 4.1 核心 Agent

| 类 | 功能 |
|----|------|
| `RequirementAgentImpl` | 核心解析。RAG 检索历史案例 → 构建 Prompt → LLM → JSON 解析 → 字段完整性校验。重试时注入上轮诊断信息 |
| `TestGenAgentImpl` | LLM 生成测试数据（previewData + per-CONDITION testCases + mockRules） |
| `DiagnosisAgentImpl` | 两层诊断：规则引擎预过滤（5 条规则，置信度 0.78-0.92）+ LLM 深度诊断（置信度 < 0.7 时触发） |
| `AgentLoopOrchestrator` | 闭环状态机：PENDING → ANALYZE → VALIDATE → DRYRUN → DECISION_POINT → PUBLISH → PUBLISHED。最多 3 轮自动重试，人工决策 10 分钟超时自动终止 |
| `MultiLoopOrchestrator` | 多接口自动闭环：拆分文档 → 创建父任务 + N 子任务 → 每个独立走完整闭环（复用 AgentLoopOrchestrator.start） |

### 4.2 意图识别与文档拆分

| 类 | 功能 |
|----|------|
| `IntentRouter` | 两层意图识别：关键词/正则快速匹配（0 token）→ LLM 兜底。低置信度标记 needUserConfirm |
| `InterfaceDevHandler` | 接口开发意图处理 |
| `KnowledgeQaHandler` | 知识问答意图处理。**先调用 RAG 检索知识库**（topK=3），将结果注入 Prompt 后由 LLM 回答 |
| `TroubleshootingHandler` | 问题排查意图处理。**先 RAG 检索历史案例，再通过 Tool Calling 循环**（最多 3 轮）让 LLM 主动查询系统配置后诊断 |
| `DocumentSplitter` | 4 级策略链：标题匹配 → 分隔线 → 端点锚点 → 全文档兜底 |
| `InterfaceDeduplicator` | 去重（method+endpoint 精确匹配 + 名称相似度告警） |
| `EndpointShortName` | 从 endpoint 派生可读 ID（`POST /api/loan/apply` → `LOAN_APPLY`） |
| `FlowTypeDetector` | 关键词打分检测文档类型：LOAN / CREDIT / REPAY |

### 4.3 Tool Calling 排查体系 <Badge text="NEW" />

用户报错时，LLM 不再是"盲猜"，而是通过工具主动查询系统状态：

```
用户贴报错日志
  → RAG 检索历史类似案例
  → LLM 调用 search_knowledge_base 查历史
  → LLM 调用 query_template 查模板内容
  → LLM 调用 query_field_mappings 查映射配置
  → LLM 调用 query_flow_definition 查流程定义
  → 基于真实数据 + 历史案例 → 精准诊断
```

**核心组件：**

| 类 | 功能 |
|----|------|
| `ToolCallingLoop` | 多轮 Tool Calling 编排（max 3 rounds）。构建 messages[] → LLM → 执行 tools → 追加结果 → 循环，直到 LLM 返回最终文本 |
| `ToolRegistry` | Tool 注册表，`toOpenAiTools()` 导出 OpenAI function calling 格式 |
| `ToolDefinition` | Tool 元数据：name / description / JSON Schema parameters |
| `ToolCall` | LLM 返回的 tool call（id / name / arguments） |
| `ToolResult` | Tool 执行结果（toolCallId / content） |

**已实现的 Tool：**

| Tool | 数据源 | 功能 |
|------|--------|------|
| `RagSearchTool` | RAG API `/search` | 搜索历史案例和业务知识 |
| `TemplateQueryTool` | `JdbcTemplate` → `fl_template` | 按 provider_code 或 template_id 查 FreeMarker 模板内容 |
| `FieldMappingQueryTool` | `JdbcTemplate` → `fl_field_mapping` | 按 template_id 查所有字段映射（fundField / sourcePath / transform） |
| `FlowDefinitionQueryTool` | `JdbcTemplate` → `fl_flow_definition` | 按 provider_code 或 flow_code 查 DAG 流程定义（含 graphData） |

**LLM Gateway 扩展：**

| 改动 | 说明 |
|------|------|
| `LlmRequest.messages` | 多轮对话消息列表（优先于 prompt），支持 system/user/assistant/tool 角色 |
| `LlmRequest.tools` | OpenAI function calling 格式的 tools 数组 |
| `LlmResponse.toolCalls` | LLM 返回的 tool_calls 列表 |
| `OpenAiCompatibleProvider` | 支持 tools/messages 构建请求 + 解析 tool_calls 响应，**向后兼容**（不带 tools 的请求行为不变） |

### 4.4 验证与写入

| 类 | 功能 |
|----|------|
| `TemplateValidator` | 调用 FundLink 模板预览接口 → 校验渲染非空、无 50002 错误、字段完整 |
| `FlowDryRunner` | 按 CONDITION 分支逐一干跑测试：创建 TEST mock 规则 → 切换数据源 → 调用流程 → 校验结果 → 恢复状态 |
| `ConfigWriter` | 幂等写入 FundLink（find-or-create provider → template(FreeMarker) → field mappings → flow(节点/边+条件表达式)） |
| `FieldCompletenessGuard` | 确保 interface_schema 每个字段都有映射（大小写不敏感） |
| `PromptBuilder` | 加载 field-catalog.yml（贷款/授信/还款字段目录），构建 System Prompt + JSON Schema + 单接口 Prompt |
| `PromptEnhancer` | 注入 RAG 历史案例作为 few-shot |

### 4.5 数据飞轮

| 类 | 功能 |
|----|------|
| `LoopTracer` | 每阶段执行记录到 ai_agent_trace；成功诊断写回 RAG |
| `FeedbackCollector` | 异步写入 ai_feedback 人工修正记录 |
| `PatternAnalyzer` | 每周日凌晨 3:00 分析修正模式 |
| `KnowledgeAutoWriter` | 每周日凌晨 3:30 将高频修正写入 RAG |

---

## 五、LLM 网关

| 组件 | 说明 |
|------|------|
| `LlmGatewayImpl` | 多 Provider 链式调用，跨 Provider 重试（max 2），异步审计记录 |
| `SmartRouter` | 按任务类型路由模型（requirement/testgen → qwen-plus；simple/diagnosis → siliconflow） |
| `OpenAiCompatibleProvider` | OpenAI 兼容 `/chat/completions` 客户端，支持 siliconflow / qwen / deepseek。**已支持 Tool Calling（tools + tool_choice + tool_calls 解析）** |
| `RagGateway` | RAG 知识库 HTTP 网关。**鉴权已移除**，直连搜索 `/search` + 知识写回 `/knowledge/upsert` |
| `AuditPersistenceService` | 异步审计入库，成本 = tokens × 单价 |
| `PiiRedactor` | 脱敏：身份证(17+X)、手机号(1[3-9]XXXXXXXXX)、银行卡(16-19位) |
| `ApiKeyEncryptor` | AES 加密 API Key |

默认 LLM 提供商链：`qwen → siliconflow → deepseek`

---

## 六、RAG 知识库 (Python FastAPI :8000)

### 6.1 服务端点

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/search` | 语义检索，支持 hybrid / rerank / adaptive 三种模式 |
| POST | `/knowledge/upsert` | 知识写回：写入 markdown 到 `D:\xyFund\信贷知识库\` |
| POST | `/token` | JWT 签发（保留兼容，鉴权已移除，开发模式无鉴权） |
| GET/POST/DELETE | `/content` | 知识库文件 CRUD |
| POST | `/build` | 触发增量/全量索引构建 |
| GET | `/build/status` | 构建进度 |
| POST | `/eval` | 启动 L1/L2/L3 评估 |

### 6.2 检索能力

| 模式 | 说明 |
|------|------|
| Hybrid | Dense (bge-small-zh-v1.5) + BM25 双路检索，RRF 融合，**默认推荐** |
| Rerank | Hybrid 粗召 → bge-reranker-v2-m3 精排，高精度场景 |
| Adaptive | 自适应检索：首次失败自动扩大范围 / 关键词重查，排查/诊断场景 |

### 6.3 知识库结构

```
D:\xyFund\信贷知识库\
├── fundaccess/          ← 资金接入（放款、还款、授信、数据源、渠道）
├── fundplat/            ← 资金平台（路由、商户、订单、资金策略）
└── financial/           ← 财务相关子域（账户/交易/转账/对账/报表）
```

---

## 七、FundLink 管理后台 API (:8080 `/api/admin`)

| Controller | 端点 | 功能 |
|------------|------|------|
| ProviderController | `/providers` | 资金方 CRUD |
| TemplateController | `/templates` | 模板 CRUD + **FreeMarker 预览**（内置 nowDate/formatAmount/enumMap 函数） |
| FieldMappingController | `/templates/{id}/mappings` | 字段映射 CRUD |
| DataSourceController | `/data-sources` | 数据源 CRUD（realUrl/mockUrl/useMock 切换） |
| MockRuleController | `/mock-rules` | Mock 规则 CRUD + **启停开关** |
| EnumMappingController | `/enum-mappings` | 枚举映射 CRUD + 枚举类型列表 |
| FlowDefinitionController | `/flows` | 流程 CRUD + **发布** (status=1) |
| FlowInstanceController | `/flow-instances` | 流程实例查询（只读） |
| ApiLogController | `/api-logs` | API 日志查询（只读） |
| CustomFunctionController | `/functions` | 自定义函数 CRUD（DB 存储，运行时未使用） |

---

## 八、上游接口网关 (:8080 `/api/upstream`)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/loan` | 贷款申请（幂等 businessNo → 异步执行 LOAN 流程） |
| POST | `/credit` | 授信申请（异步执行 CREDIT 流程） |
| POST | `/repay` | 还款申请（异步执行 REPAYMENT 流程） |
| GET | `/result/{businessNo}` | 查流程结果 + contextData |
| POST | `/flows/{id}/dry-run` | **同步**执行流程（AI 干跑测试用） |

---

## 九、流程引擎 (DAG)

**`FlowEngine` — 6 种节点类型：**

| 节点类型 | 功能 |
|----------|------|
| START | 起始节点 |
| END | 结束节点 |
| CONDITION | 条件路由（SpEL 表达式在边上，按序评估） |
| DATA_COLLECT | 数据采集（mockUrl/realUrl，POST 请求） |
| TEMPLATE_RENDER | FreeMarker 模板渲染（含 enumMap / formatAmount / nowDate 函数） |
| SEND_TO_FUND | 发送资金方（⚠️ 当前为 Stub，返回固定 `{code:"0000"}`） |

**特性：**
- 异步执行（`execute`）+ 同步执行（`executeSync`）
- 自动创建 `fl_flow_instance` 记录（RUNNING → SUCCESS / FAILED）
- SpEL 条件表达式（`#root.request.*`）

---

## 十、Mock 服务

- `GET/POST/PUT /api/mock/{sourceCode}` — SpEL 匹配规则 → 返回 mock 响应（含模拟延迟）
- `POST /api/mock/debug` — 在线调试：查看匹配到的规则 + 响应
- 优先级：条件匹配 > 默认规则

---

## 十一、数据库表 (MySQL `fundlink`)

### AI 表 (ai_*)

| 表 | 说明 |
|----|------|
| `ai_task` | 闭环任务（含 parent_task_id / interface_id 多接口支持） |
| `ai_llm_audit` | LLM 调用审计（call_id, provider, model, tokens, cost, latency, trace_id） |
| `ai_agent_trace` | Agent 每阶段执行 Trace（含 tool_calls JSON，Tool Calling 时写入） |
| `ai_feedback` | 人工修正反馈（数据飞轮） |
| `ai_config_review` | 配置审核记录 (PENDING/APPROVED/REJECTED) |

### FundLink 业务表 (fl_*)

| 表 | 说明 |
|----|------|
| `fl_provider` | 资金方（provider_code, provider_name, base_url, mock_url, timeout_ms） |
| `fl_data_source` | 数据源（含 mockUrl / useMock） |
| `fl_mock_rule` | Mock 规则 |
| `fl_template` | FreeMarker 模板（provider_id 外键，content 为模板文本） |
| `fl_field_mapping` | 字段映射（template_id 外键，fund_field / source_path / field_type / transform） |
| `fl_enum_mapping` | 枚举映射 |
| `fl_flow_definition` | 流程定义（provider_id 外键，graphData JSON 含 nodes/edges） |
| `fl_flow_instance` | 流程执行实例 |
| `fl_node_instance` | 节点执行实例（⚠️ 未使用） |
| `fl_custom_function` | 自定义函数（⚠️ 未使用） |
| `fl_api_log` | API 日志（⚠️ 只读，无生产者写入） |

---

## 十二、已知待完善项

| 项目 | 状态 |
|------|:--:|
| SEND_TO_FUND 节点 → 真实资金方 HTTP 调用 | ⚠️ Stub |
| `/ai/tasks` (TaskCenter) 页面 | ⚠️ Mock 数据 |
| `/ai/trace` (AgentTrace) 页面 | ⚠️ Mock 数据 |
| Claude Provider Bean 注册 | ⚠️ 配置了但未注册 |
| `fl_api_log` 写入逻辑 | ⚠️ 无生产者 |
| `fl_node_instance` 记录 | ⚠️ 未使用 |
| `fl_custom_function` 运行时执行 | ⚠️ 未接入 |
| 排查结果归档（创建 ai_task + 回写 RAG） | ⚠️ 排查不走数据飞轮 |
| QA / 排查 创建 ai_task 记录 | ⚠️ 有审计但无任务记录 |
| 多轮对话记忆（跨请求上下文） | ⚠️ 每次请求独立无状态 |
| 排查人工反馈（踩/赞 + 修正） | ⚠️ 无反馈入口 |
