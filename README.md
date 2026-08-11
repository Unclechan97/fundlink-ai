# FundLink AI

AI 驱动的资金接入全生命周期管理平台 — 接口文档解析 / 闭环验证 / 知识问答 / 问题排查。

## 架构

```
fundlink-ai/
├── fundlink-ai-core/   ← Agent / Gateway / Tools 核心逻辑
└── fundlink-ai-app/    ← Web 层 (Controller + SSE)
```

- **LLM Gateway**: 多 Provider 链式调用（Qwen → SiliconFlow → DeepSeek），SmartRouter 按任务类型路由
- **Agent 闭环**: 文档解析 → 模板验证 → 干跑测试 → 诊断 → 人工决策 → 发布
- **Tool Calling**: LLM 主动查询系统配置（模板 / 字段映射 / 流程定义）精准诊断
- **RAG 知识库**: Hybrid 检索 + 排查案例自动归档 + 数据飞轮反馈回路

## 快速启动

```bash
# 1. 启动 RAG 知识库 (Python :8000)
cd rag-system && uvicorn main:app --port 8000

# 2. 启动 AI 后端 (Spring Boot :8081)
cd fundlink-ai && mvn spring-boot:run -pl fundlink-ai-app

# 3. 启动前端 (Vite :3000)
cd fundlink-ui && npm run dev
```

## 演示

<video src="video/fundlink-ai.mp4" controls width="800"></video>
