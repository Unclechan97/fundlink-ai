# FundLink AI — 项目状态文档

> 2026-08-08 | 供后续开发参考

---

## 一、仓库地址

| 仓库 | 地址 |
|------|------|
| AI 后端 | https://github.com/Unclechan97/fundlink-ai |
| 前端 | https://github.com/Unclechan97/fundlink-ui |
| RAG | https://github.com/Unclechan97/FundLink-RAG |

---

## 二、系统架构

```
fundlink-ui (:3000)          ← React 19 + antd 6 + React Flow
    │
    ├─ /api/*     → fundlink (:8080)        ← Spring Boot 3.2.5 原有系统
    │   ├─ fl_provider, fl_template, fl_field_mapping, fl_flow_definition...
    │   └─ POST /api/admin/templates/{id}/preview  ← FreeMarker 预览
    │
    └─ /api/ai/*  → fundlink-ai-app (:8081) ← Spring Boot 3.2.5 AI 平台
        ├─ fundlink-ai-core
        │   ├─ gateway/          LLM网关 (JDK HttpClient)
        │   ├─ agent/            Agent引擎 + PromptBuilder + ConfigWriter
        │   ├─ feedback/         FeedbackCollector + PatternAnalyzer
        │   ├─ entity/ mapper/   MyBatis-Plus ai_* 表
        │   └─ tools/            FundLinkTool, RagTool, DbReadTool
        └─ fundlink-ai-app
            └─ controller/       CopilotController, AuditController

rag-system (:8000)            ← FastAPI + Qdrant + BGE-small + BM25
    └─ fundlink_knowledge collection
```

---

## 三、已完成功能

### 3.1 LLM Gateway
- `LlmGatewayImpl` — 路由 + 审计 + 降级
- `QwenProvider` — JDK HttpClient 调千问 (qwen-plus)
- `PiiRedactor` — 身份证/手机号/银行卡脱敏
- `ApiKeyEncryptor` — AES 加密存储 Key
- 审计日志: `ai_llm_audit` 表，每次调用落库
- 日志标签: `[LLM]` `[GATEWAY]` `[REQ-AGENT]` `[ENHANCER]`

### 3.2 Prompt 系统
- `PromptBuilder.java` — 固化 SYSTEM_PROMPT + 动态字段上下文
- `field-catalog.yml` — loan/credit/repay 三级 DTO 字段目录
- `PromptEnhancer.java` — RAG 检索 Few-shot 注入
- **AI 不再生成 FreeMarker** — 模板由 ConfigWriter 从 mappings 构建

### 3.3 Requirement Agent（核心链路）
- `POST /api/ai/analyze` → Qwen 解析接口文档
- 输出: `provider_config` + `interface_schema` + `field_mappings` + `flow_dsl`
- `parseSafely()` — JsonNode 安全解析，字段缺失不抛 NPE

### 3.4 ConfigWriter（配置写入）
- `POST /api/ai/apply` → 4 步写入 FundLink:
  1. `POST /api/admin/providers` → 创建资金方
  2. 从 mappings 全量构建 FreeMarker → `POST /api/admin/templates`
  3. 逐条 `POST /api/admin/templates/{id}/mappings`
  4. `POST /api/admin/flows` → 创建流程定义(含 CONDITION 节点 label/expression 同步)
- FreeMarker 使用 sourcePath 全名: `${userInfo.realName}`, `${formatAmount(loanInfo.amount)}`

### 3.5 TestGen Agent
- `TestGenAgentImpl` — 生成 Mock 规则 + 测试用例

### 3.6 Diagnosis Agent
- `DiagnosisAgentImpl` — 规则引擎 + LLM 诊断
- 规则: 模板错误/数据源超时/条件表达式异常

### 3.7 反馈系统
- `FeedbackCollector` — 异步记录 AI vs 人工 Diff
- `PatternAnalyzer` — 每周 SQL 聚合高频修正模式
- `KnowledgeAutoWriter` — 高频模式 → RAG 知识条目

### 3.8 SmartRouter
- 任务类型 → 模型选择 (simple→cheap, complex→capable)

### 3.9 审计
- `AuditController` — CSV 导出 + 成本汇总

### 3.10 Docker
- `docker-compose.yml` + `Dockerfile`

### 3.11 前端 Copilot V2
- `/ai/copilot` — 对话式 AI 助手
- 字段映射表: 可编辑 (fundField/sourcePath/transform) + 增删行 + 一键采纳
- React Flow 流程图: 节点拖拽 + 配置编辑 + 条件表达式
- 写入门禁: 全部采纳 + 流程采纳 → 才可写入
- `/ai/tasks` — 接入任务看板
- `/ai/trace` — Agent 执行追踪

---

## 四、数据库表 (ai_*)

| 表 | 用途 |
|----|------|
| `ai_task` | AI 任务记录 |
| `ai_llm_audit` | LLM 调用审计日志 (金融合规核心) |
| `ai_agent_trace` | Agent 执行 Trace |
| `ai_feedback` | 反馈记录 (数据飞轮载体) |
| `ai_config_review` | 配置审核记录 |

---

## 五、测试

- 19 个 TDD 单元测试 (H2 + Mock Provider)
- 覆盖: Entity/Mapper, Gateway, RequirementAgent, TestGenAgent, DiagnosisAgent

---

## 六、待完善

### 6.1 模板渲染验证
- FreeMarker 中 `${formatAmount(loanInfo.amount)}` 能否被 FundLink 的 `TemplateRenderService` 正确渲染
- FundLink 的 `formatAmount` 函数签名: `formatAmount(BigDecimal)` → 需要确认入参类型匹配
- `enumMap` 函数当前未在模板中使用（枚举已下掉），如需恢复需确保 FundLink 已注册

### 6.2 流程执行验证
- AI 生成的 flow 写入后处于草稿状态(status=0)，需要手动在 Flow 页面点"Publish"后 `FlowEngine` 才能执行
- CONDITION 节点的 SpEL 表达式是否被 `FlowDefinition.parse()` 正确解析

### 6.3 ConfigWriter 容错
- 当 FundLink API 返回的 `data` 不是预期的 Map/Number 类型时，catch 块只记录日志——考虑更明确的错误返回
- 写入失败时用户看不到具体哪一步失败——返回更细粒度的错误信息

### 6.4 多接口支持
- 当前一次只解析一个接口。真实文档含多个接口 (loan/credit/repay)
- 需要支持批量解析 + 按接口类型分发

### 6.5 前端 FlowEdit 体验
- Copilot 页面的 React Flow 缺少画布缩放、小地图
- 删除节点/边后没有撤销功能

### 6.6 SmartRouter 未启用
- `SmartRouter.java` 已实现但 `LlmGatewayImpl` 没调用——当前所有请求都走 Qwen

### 6.7 千问返回稳定性
- Qwen 有时不输出 `data.config` (node.data 部分字段缺失)
- 有时 sourcePath 输出 `userInfo.realNam` 截断 ← 需在 parseSafely 层做字段名校验

### 6.8 field-catalog.yml CI 检查
- catalog 中的字段应与实际 DTO 类同步 → 加集成测试自动校验

---

## 七、上下游信息

### 上游 (AI 依赖)
| 系统 | 地址 | 用途 |
|------|------|------|
| Qwen (千问) | dashscope.aliyuncs.com | LLM 推理, 模型 qwen-plus |
| RAG | localhost:8000 | 知识检索 /search, 文档入库 /documents/ingest, 知识写回 /knowledge/upsert |
| Qdrant | localhost:6333 | 向量库, collection: fundlink_knowledge_hybrid |

### 下游 (AI 写入)
| 系统 | 地址 | API |
|------|------|-----|
| FundLink 后端 | localhost:8080 | `/api/admin/providers`, `/api/admin/templates`, `/api/admin/templates/{id}/mappings`, `/api/admin/flows` |

### FundLink 核心表 (fl_*)
| 表 | AI 写入 | 用途 |
|----|---------|------|
| `fl_provider` | ✅ 新建 | 资金方 |
| `fl_template` | ✅ 新建 | FreeMarker 模板 |
| `fl_field_mapping` | ✅ 新建 | 字段映射 |
| `fl_flow_definition` | ✅ 新建 (status=0 草稿) | 流程定义 (graphData JSON) |
| `fl_data_source` | ❌ 不写入 | 数据源(RISK/CORE/PAYMENT)，手工维护 |
| `fl_enum_mapping` | ❌ 不写入 | 枚举映射，手工维护 |
| `fl_custom_function` | ❌ 不写入 | 自定义函数，手工维护 |

### FundLink FlowDefinition.parse() 格式
```json
{
  "nodes": [
    {"id":"n1","type":"START","data":{"label":"开始"}},
    {"id":"n2","type":"DATA_COLLECT","data":{"label":"获取风控","config":{"dataSourceCode":"RISK","outputKey":"riskData"}}},
    {"id":"n3","type":"TEMPLATE_RENDER","data":{"label":"渲染报文","config":{"templateCode":"LOAN_REQ","outputKey":"reqMsg"}}},
    {"id":"n6","type":"CONDITION","data":{"label":"风控判断","config":{"expression":"#root.riskData.level == 'A'"}}},
    {"id":"n7","type":"SEND_TO_FUND","data":{"label":"发送","config":{"url":"http://fund/api","requestKey":"reqMsg","responseKey":"fundResp"}}},
    {"id":"n8","type":"END","data":{"label":"结束"}}
  ],
  "edges": [
    {"id":"e6","source":"n6","target":"n7","label":"A级","conditionExpr":"#root.riskData.level == 'A'"},
    {"id":"e7","source":"n6","target":"n8","label":"非A级"}
  ]
}
```
- 边上的 `conditionExpr` 用于后端执行
- CONDITION 节点上的 `data.config.expression` 仅用于前端编辑面板展示

---

## 八、环境变量

| 变量 | 用途 | 示例 |
|------|------|------|
| `MYSQL_PASSWORD` | MySQL 密码 | chc1234567 |
| `QWEN_API_KEY` | 千问 API Key | sk-ws-H.xxx |
| `SPRING_AI_OPENAI_API_KEY` | Spring AI 占位 (不实际使用) | test |
| `JAVA_HOME` | JDK 路径 | C:\Program Files\Java\jdk-17 |

---

## 九、启动命令

```bash
# FundLink 后端 (8080)
cd D:\xyFund\fundlink\fundlink && java -jar fundlink-app/target/fundlink-app-1.0.0.jar

# AI 后端 (8081)
export JAVA_HOME='C:\Program Files\Java\jdk-17' MYSQL_PASSWORD=chc1234567 QWEN_API_KEY=sk-xxx SPRING_AI_OPENAI_API_KEY=test
cd D:\xyFund\fundlink-ai && java -jar fundlink-ai-app/target/fundlink-ai-app-1.0.0.jar

# RAG (8000)
cd D:\xyFund\rag-system && python api.py

# 前端 (3000)
cd D:\xyFund\fundlink\fundlink-ui && npm run dev
```

---

## 十、下一步：Agent Loop 设计思路

目标: **生成 → 配置 → Preview → 自测 → 修正 → 发布** 全自动化

```
用户输入接口文档
    │
    ▼
┌─ Agent Loop ──────────────────────────────────┐
│                                                │
│  1. RequirementAgent 解析 → 字段映射 + 流程    │
│  2. ConfigWriter 写入 (草稿)                   │
│  3. FundLink Preview 渲染验证                  │
│     ├─ 渲染成功 → 继续                         │
│     └─ 渲染失败 → 错误信息 → 回到步骤1修正     │
│  4. TestGenAgent 生成 Mock + 测试用例          │
│  5. FlowEngine.executeSync() 干跑测试          │
│     ├─ 通过 → 继续                             │
│     └─ 失败 → DiagnosisAgent 诊断 → 回步骤1    │
│  6. 全部通过 → 发布 (status=0→1)               │
│                                                │
└────────────────────────────────────────────────┘
```

关键组件:
- `AgentLoopOrchestrator` — 状态机驱动, 重试上限 3 次
- `TemplateValidator` — 调 FundLink Preview API 验证模板可渲染
- `FlowDryRunner` — 调 FlowEngine.executeSync() 干跑
- `LoopTracer` — 记录每轮修正, 沉淀为 RAG 知识
