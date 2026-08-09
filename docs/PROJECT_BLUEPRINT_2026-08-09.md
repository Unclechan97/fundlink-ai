# FundLink AI — 项目蓝图

> 2026-08-09 | 当前状态 + 未来路线

---

## 一、系统架构

```
┌──────────────────────────────────────────────────────┐
│  fundlink-ui (React 19, 端口 3000)                    │
│  AI Copilot: 手动模式 + 自动闭环模式                    │
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
│ SilconFlow/Qwen/ │          │ Python + Qdrant      │
│ DeepSeek         │          │ 语义检索 + 知识写回  │
└──────────────────┘          └──────────────────────┘
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
| MySQL (fundlink-ai) | AI 数据: ai_task, ai_agent_trace |
| PostgreSQL + Redis | LiteLLM (未引入) |
| Qdrant | RAG 向量存储 |

---

## 二、已实现功能清单

### 后端 — Agent Loop 闭环引擎

| 模块 | 功能 | 说明 |
|------|------|------|
| **LLM Gateway** | 多 Provider 路由 | SmartRouter + OpenAiCompatible API，支持 SiliconFlow/Qwen/DeepSeek |
| | Fallback Chain | 主 Provider 失败自动切换备选 |
| | 审计记录 | 每次 LLM 调用写入 ai_llm_audit 表 |
| **RequirementAgent** | 接口文档解析 | LLM 分析文档 → 字段映射 + 流程 DSL |
| | 多轮修正 | 接收 previousErrors，Prompt 注入修正建议 |
| | 安全解析 | JsonNode 逐字段解析，缺字段不崩 |
| **ConfigWriter** | 配置写入 | 幂等 get-or-create Provider/Template/Flow/FieldMapping |
| | FreeMarker 模板生成 | 根据字段映射自动生成 JSON 模板 |
| **TestGenAgent** | 测试数据生成 | LLM 生成 previewData + 按 CONDITION 分支的 testCases |
| | Mock 规则生成 | 每条分支生成对应 mock rule |
| **TemplateValidator** | 模板验证 | 调 FundLink Preview API 验证渲染结果 |
| | 字段覆盖检查 | 逐一检查映射字段是否出现在渲染输出中 |
| **FlowDryRunner** | 分支干跑 | Mock 注入 + executeSync，逐分支验证 |
| | 冒烟测试 | 无 CONDITION 分支时执行基础冒烟 |
| **DiagnosisAgent** | 规则引擎 | 5 种常见错误自动诊断 (FreeMarker/SpEL/数据源/enumMap/链式) |
| | LLM 深度诊断 | 规则覆盖不到时调 LLM 做结构化诊断 |
| **LoopTracer** | 轨迹记录 | 每步写入 ai_agent_trace 表 |
| | 知识回写 | 成功修正后自动写回 RAG 知识库 |
| **RAG 集成** | 语义检索 | RagGateway 统一封装，PromptEnhancer 注入 few-shot 案例 |
| | 知识写回 | 高频修正模式自动生成知识条目 |
| **API** | REST + SSE | POST /loop 创建任务，GET /loop/{id}/stream SSE 推送，POST /decide 人工决策 |

### 前端 — AI Copilot

| 功能 | 说明 |
|------|------|
| **手动模式** | 贴文档 → AI 解析 → 审核字段映射表 → 可视化流程图编辑 → 写入 FundLink |
| **自动闭环模式** | 贴文档 → 一键启动 → SSE 实时进度条 + 阶段日志 + 决策按钮 |
| **手动/自动切换** | Segmented 控件，两种模式共享输入 |
| **状态持久化** | sessionStorage 保存输入内容和解析结果，切 tab/刷新不丢 |
| **自动任务恢复** | 自动模式下切走后回来自动重连 SSE，恢复未完成任务 |
| **决策面板** | 5 种决策按钮 (发布/重试/跳过/编辑后重试/放弃)，防重复点击 |
| **EDIT_AND_RETRY** | 决策点可打开编辑弹窗，修改字段映射后重新提交 |
| **断连重连** | SSE 断线后显示提示 + 手动重连按钮 |

---

## 三、待优化 (Prompt)

> 以下两点是 Prompt 层面的优化，不改代码架构

1. **必填字段强制覆盖**：接口文档中标注必填的字段，必须生成对应的字段映射。找不到匹配的内部数据源时，该字段仍需映射但 sourcePath 留空，标注 `TODO`，不凭空编造。

2. **flowType 自动识别**：LLM 根据文档内容自动判断接口类型（LOAN/CREDIT/REPAY），不需要前端手动选择。Prompt 中提供各类型的关键词特征，让 LLM 在解析阶段就输出 flowType。

---

## 四、未来开发计划

### P0 — 体验修复

| # | 项目 | 说明 |
|---|------|------|
| 1 | **SSE 心跳保活** | 免费 LLM 慢，当前 SseEmitter 60s 超时，需对齐 LLM timeout (20min) |
| 2 | **Prompt 必填字段优化** | 见 §三 |
| 3 | **flowType 自动识别** | 见 §三 |

### P1 — 代码质量

| # | 项目 | 说明 |
|---|------|------|
| 4 | **Provider 合并** | Qwen/DeepSeek/SiliconFlow 三个类 90% 重复，合为 OpenAiCompatibleProvider |
| 5 | **Spring AI 替换** | 已在 pom 依赖中，用 ChatClient 替换手写 HttpClient |
| 6 | **EDIT_AND_RETRY 增强** | 编辑弹窗加流程图编辑，加注释/备注字段 |

### P2 — 功能增强

| # | 项目 | 说明 |
|---|------|------|
| 7 | **ConfigWriter 数组路径处理** | `repayPeriods[].xxx` 自动转 `<#list>` 循环，防止 FreeMarker 50002 |
| 8 | **Prompt 加 REPAY 样例** | PromptBuilder 目前只有 loan 的字段目录和流程样例 |
| 9 | **智能重试** | diagnosis confidence > 0.9 时自动 RETRY，跳过人工确认 |
| 10 | **决策倒计时** | 前端显示 10 分钟决策剩余时间 |

### P3 — 长远规划

| # | 项目 | 说明 |
|---|------|------|
| 11 | **Orchestrator 单元测试** | mock 全部依赖，验证状态转换 |
| 12 | **多接口批量解析** | 一次文档含多种接口，按 flowType 分发 |
| 13 | **SmartRouter 自动切换** | 余额检测 + 自动切 provider |
| 14 | **Claude Haiku Provider** | 诊断任务用 Claude，精确度更高 |
| 15 | **前端 E2E 测试** | Playwright 覆盖自动闭环完整流程 |

---

## 五、上下游依赖

### 上游 (被谁调用)

| 调用方 | 方式 | 说明 |
|--------|------|------|
| fundlink-ui | HTTP + SSE | AI Copilot 页面调用 |
| Postman / curl | HTTP | 开发调试 |

### 下游 (调用谁)

| 服务 | 端口 | 用途 |
|------|------|------|
| SiliconFlow API | 外部 | LLM 推理 (Qwen3-8B 免费) |
| 阿里云 DashScope | 外部 | LLM 推理 (Qwen-Plus 备用) |
| DeepSeek API | 外部 | LLM 推理 (备用) |
| FundLink Admin | 8080 | 配置读写 (Provider/Template/Flow/Mock) |
| FundLink Upstream | 8080 | 流程干跑 (dry-run) |
| RAG Service | 8000 | 语义检索 + 知识写回 |
| Qdrant | 6333 | RAG 向量存储 |
| MySQL | 3306 | AI 任务数据 |

### 环境变量

| 变量 | 用途 |
|------|------|
| `MYSQL_USER` / `MYSQL_PASSWORD` | 数据库连接 |
| `SILICONFLOW_API_KEY` | 硅基流动 API Key |
| `QWEN_API_KEY` | 阿里云百炼 API Key |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |

### 启动顺序

```bash
# 1. MySQL + Qdrant
# 2. RAG Service
cd D:\xyFund\rag-system && python api.py

# 3. FundLink 核心
cd D:\xyFund\fundlink\fundlink
java -jar fundlink-app/target/fundlink-app-1.0.0.jar

# 4. AI 后端
# IDEA Run: FundLinkAiApplication
# Env: MYSQL_PASSWORD=xxx SILICONFLOW_API_KEY=xxx

# 5. 前端
cd D:\xyFund\fundlink\fundlink-ui && npm run dev
```
