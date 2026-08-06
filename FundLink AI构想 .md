# FundLink AI 

FundLink AI —— AI\-Native Financial Integration Platform 技术方案

## 1\. 系统目标

### 1\.1 现状流程（资金方接入）

资金方接口文档 → 研发阅读文档 → 配置接口模板 → 配置业务流程 → 配置Mock数据 → 人工测试 → 上线 → 线上问题排查

### 1\.2 现存痛点

- 接口文档理解高度依赖研发经验，新人上手成本高

- 字段映射工作重复度高、机械化，人力浪费严重

- 业务流程配置逻辑复杂，配置门槛高、周期长

- 测试数据人工准备成本高、覆盖场景不全

- 线上问题定位依赖资深业务研发，排查效率低、不可沉淀

### 1\.3 建设目标

搭建**AI 驱动的资金接入全生命周期管理平台**，完整覆盖资金接入全流程，实现智能化、标准化、可沉淀、可观测的接入体系。

全流程链路：需求理解 → 配置生成 → 人工审核 → 自动测试 → 运行监控 → 智能诊断 → 知识沉淀

## 2\. 总体架构

整体分层架构自上而下如下，兼顾AI智能编排与原有系统稳定执行：

用户 → FundLink Console（React \+ TypeScript） → API Gateway

API Gateway 分为两大核心层级：**AI 编排层**、**平台服务层**

### 2\.1 AI Orchestration Layer（AI编排层）

包含四大智能Agent：需求解析Agent、测试Agent、诊断Agent、知识Agent

### 2\.2 Platform Service（平台服务层）

核心服务：配置服务、流程服务，无缝对接原有Java系统

### 2\.3 底层能力引擎

模板引擎、流程引擎、API运行时

### 2\.4 数据与中间件底座

MQ、数据库、外部合作方

### 2\.5 基础设施

MySQL、Redis、Qdrant、Kafka、OpenTelemetry

## 3\. 核心设计原则

严格遵循**AI分层隔离原则**，适配金融场景高安全、可审计、高可控要求：

### 3\.1 禁忌设计（风险方案）

LLM直接调用银行交易接口，存在不可控、不可审计、金融合规不通过的核心风险

### 3\.2 标准设计（合规方案）

LLM智能生成配置 → 人工审核校验 → Java Runtime正式执行

### 3\.6 分层定位

- **AI 定位：智能决策层（Intelligence Layer）**：负责理解、生成、分析、诊断、沉淀

- **Java 定位：执行落地层（Execution Layer）**：负责真实交易、流程执行、接口调用，保障稳定可控

## 4\. AI Agent 体系设计

采用**路由式多Agent架构**，统一入口、分工协作，覆盖全业务场景

### 4\.1 核心架构

Agent Router（统一路由分发）

分发三大核心业务Agent：需求解析Agent、测试Agent、诊断Agent

### 4\.2 Agent配套工具体系

- 需求Agent：配置工具、流程工具、知识工具

- 测试Agent：Mock工具、测试工具

- 诊断Agent：日志工具、数据库工具、链路追踪工具

## 5\. 需求理解 Agent（Requirement Understanding Agent）

### 5\.1 核心目标

输入自然语言业务需求\+各类接口文档，自动解析、结构化输出标准化接口配置、字段映射、业务流程，替代人工阅读理解与配置工作。

### 5\.2 输入源支持

多格式文档适配：PDF、Markdown、Word、OpenAPI规范文档

示例场景：放款申请接口

请求地址：POST /loan/apply

请求字段：loanNo、amount、customerId

响应字段：code、message、loanStatus

### 5\.3 核心执行Pipeline

文档上传 → 文档解析器 → 结构化提取 → LLM智能理解 → 结构校验器 → 配置生成器

### 5\.4 标准化输出内容

#### 5\.4\.1 接口Schema

```Plain Text
{
 "interface":"loan_apply",
 "fields":[
 {
 "name":"loanNo",
 "type":"string",
 "required":true
 }
 ]
}
```

#### 5\.4\.2 字段映射模板配置

```Plain Text
{
"loanNo":"$.orderNo"
}
```

#### 5\.4\.3 业务流程DSL

```Plain Text
workflow:
 name:loan_flow
 steps:
 - loan_apply
 - wait:
      seconds:5
 - loan_query
```

## 6\. 智能配置助手（Configuration Copilot）

### 6\.1 设计初衷

金融场景禁止AI直接修改、发布线上配置，引入**人机协同审核机制**，兼顾智能化效率与金融合规安全。

### 6\.2 配置发布流程

AI智能生成配置 → 差异对比Diff展示 → 人工审核Review → 审批通过Approve → 正式发布

机制类比：GitHub Pull Request 代码审核流程

### 6\.3 配置版本管理体系

数据库核心表：config\_version

核心字段：id、config\_type、content、version、creator、reviewer、status

核心能力：版本迭代、一键回滚、全链路审计、操作留痕

## 7\. 工作流智能编排（Workflow智能编排）

### 7\.1 设计定位

不替代原有成熟Java工作流引擎，基于现有引擎做**AI增强能力**，通过AI自动生成标准化流程DSL，降低人工编排成本。

### 7\.2 业务流程示例（贷款流程）

LoanApply（放款申请） → 条件分支判断 → LoanQuery（订单查询） → 流程成功

### 7\.3 前端可视化能力

基于 React Flow 实现流程可视化，实时展示：流程节点、节点状态、执行进度、运行耗时、异常信息

## 8\. 测试生成 Agent（核心亮点模块）

### 8\.1 核心能力

基于接口Schema自动生成全覆盖Mock测试数据、标准化测试用例，实现自动化测试闭环。

### 8\.2 输入参数

接口结构化字段：amount、loanStatus、customerType等

### 8\.3 AI自动生成内容

#### 8\.3\.1 Mock测试数据

正常场景：`{amount:10000, status:"SUCCESS"}`

异常场景：`{status:"REJECT", reason:"risk"}`

#### 8\.3\.2 全覆盖测试用例

- Case1：正常放款流程

- Case2：额度不足异常场景

- Case3：接口超时异常场景

- Case4：签名失败异常场景

### 8\.4 测试执行链路

测试Agent → Mock平台 → 现有Java业务系统 → 结果校验与统计

## 9\. 智能诊断 Agent（AI Diagnosis Agent）

### 9\.1 业务场景

针对银行放款失败、接口调用异常、流程中断等线上问题，实现**全自动根因排查**。

### 9\.2 自动排查链路

诊断Agent自动联动查询：订单数据 → 接口日志 → MQ消息记录 → 数据库数据 → 链路Trace信息

### 9\.3 智能输出结果

- 问题定位：loan\_query接口调用失败

- 直接原因：字段contractNo为空

- 根本根因：模板字段映射配置错误

- 优化建议：新增字段映射规则 contractNo \-\> contract\_id

## 10\. RAG \+ 集成记忆体系（Integration Memory）

### 10\.1 能力升级定位

区别于传统通用知识库，打造**金融集成专属知识体系**，沉淀资金接入全量经验，实现问题可追溯、经验可复用。

### 10\.2 知识存储分类

- 文档知识（非结构化）：基于Qdrant向量数据库存储

- 结构知识（结构化）：基于MySQL存储接口、字段、流程规范

- 关系知识（可选）：基于Neo4j存储业务关联关系

### 10\.3 智能检索链路

用户查询请求 → 混合检索（Hybrid Search） → 重排序（Rerank） → 上下文构建 → LLM智能问答

## 11\. Agent工具标准体系（MCP）

### 11\.1 技术引入

引入MCP协议实现Agent工具标准化管理

### 11\.2 架构链路

智能Agent → MCP统一协议层 → 各类业务工具

### 11\.3 标准化工具集

配置工具、Mock工具、数据库工具、日志工具、流程工具、链路追踪工具

### 11\.4 核心优势

- 工具能力标准化，统一调用规范

- 支持精细化权限控制，适配金融安全要求

- 插件化扩展，新增工具无侵入、低成本

## 12\. Agent可观测体系（Agent Observability）

### 12\.1 观测核心维度

全链路记录Agent执行全过程：任务Trace、Agent决策、LLM调用、工具调用、最终执行结果

### 12\.2 核心数据结构（agent\_trace）

trace\_id、task\_id、agent\_name、input、output、latency、token、status

### 12\.3 技术栈支撑

OpenTelemetry、Langfuse、Prometheus、Grafana

## 13\. 前端控制台设计

### 13\.1 前端技术栈

React18、TypeScript、Ant Design Pro、React Flow、ECharts、SSE/WebSocket

### 13\.2 核心页面模块

#### 13\.2\.1 接入任务中心

展示各资金方接入进度、状态、完成度、当前执行阶段（例：招商银行\-测试中\-80%\-Mock测试阶段）

#### 13\.2\.2 AI Copilot智能助手

对话式交互\+配置差异Diff可视化审核

#### 13\.2\.3 流程监控中心（Workflow Monitor）

可视化流程画布，实时展示节点状态、执行耗时、运行日志、异常告警

#### 13\.2\.4 Agent追踪中心（Agent Trace）

完整查看Agent执行链路：Prompt入参、工具调用过程、执行结果、耗时统计

#### 13\.2\.5 自动化测试中心（Test Center）

展示测试用例总数、成功/失败数量，提供AI智能测试分析、异常归因能力

## 14\. 最终技术栈汇总

### 14\.1 后端基础栈

Java、Spring Boot、MyBatis、MySQL、Redis、Kafka

### 14\.2 AI能力栈

LangChain4j / Spring AI、通用大模型兼容接口、Claude、GPT、Qwen、DeepSeek

### 14\.3 Agent能力栈

多智能体架构、函数调用、MCP协议、结构化输出、人机在环（Human\-in\-the\-loop）

### 14\.4 RAG知识库栈

Qdrant向量库、BGE\-M3嵌入模型、BM25检索、Reranker重排、RAGAS评估

### 14\.5 工作流栈

自研Java工作流引擎、状态机、自定义DSL

### 14\.6 前端栈

React、TypeScript、React Flow、ECharts

### 14\.7 可观测栈

OpenTelemetry、Langfuse、Prometheus、Grafana

