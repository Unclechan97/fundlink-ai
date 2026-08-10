# 03 — 并行子 Agent 处理

> 优先级: P1（多接口性能核心）
> 依赖: 02-DocumentSplitter, ConfigWriter（已有）, RequirementAgent（已有）

---

## 1. 目标

拆分出多个接口后，用独立子 Agent 并行处理每个接口，使总耗时接近最慢单个接口的耗时，而非累加。

## 2. 为什么用子 Agent 模式

| 维度 | 串行处理 | 并行子 Agent |
|------|---------|-------------|
| 耗时 | N × 单接口耗时 | ≈ max(单接口耗时) |
| Prompt 质量 | 全文可能污染 | 独立 Prompt，聚焦单个接口 |
| 错误隔离 | 一个失败全停 | 单个失败不影响其他 |
| 重试 | 整体重试 | 按接口独立重试 |

## 3. 并行处理架构

```
InterfaceSegment[] interfaces = splitter.split(documentText);

// Phase 0: 全局提取（只做一次）
GlobalContext global = extractGlobalContext(fullDocument, providerCode);
// → Provider 信息（先同步创建）、公共字段、全局枚举

// Phase 1: 并行 ANALYZE
List<CompletableFuture<InterfaceResultItem>> futures = interfaces.stream()
    .map(seg -> CompletableFuture.supplyAsync(() -> {
        RequirementResult result = requirementAgent.analyze(
            seg.getSectionText(),    // 只传该接口的片段
            providerCode,
            seg.getFlowType(),
            previousErrors
        );
        return InterfaceResultItem.of(seg, result);
    }, interfaceExecutor))
    .toList();
CompletableFuture.allOf(...).join();

// Phase 2: 并行写入 ConfigWriter
successItems.stream()
    .map(item -> CompletableFuture.supplyAsync(() ->
        configWriter.writeAll(item.getResult(), providerCode,
            item.getFlowType(), item.getInterfaceId()),
        writeExecutor
    ))
    ...

// Phase 3: 并行 VALIDATE + DRYRUN（仅自动模式）
// 每个接口独立: ANALYZE → write → VALIDATE → DRYRUN
```

## 4. 并发控制

### 配置

```yaml
fundlink:
  interface:
    max-parallel: 5            # 最大并行接口数
    parallel-timeout-minutes: 10
    max-count: 50              # 超过此数量拒绝自动处理
```

### 决策树

```
                    ┌─────────────┐
                    │ 拆分结果 N   │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
           N = 1         N = 2-5       N > 5
              │            │            │
              ▼            ▼            ▼
         串行处理      全并行处理    前端确认数量
         (现有逻辑)    (5 线程)    ┌────┴────┐
                                   ▼         ▼
                              用户选部分   用户确认全部
                              → 并行      → 分批并行
                                           (每批 5 个)
```

### 分批逻辑

```java
int batchSize = config.getMaxParallel();
List<List<InterfaceSegment>> batches = partition(interfaces, batchSize);
for (List<InterfaceSegment> batch : batches) {
    processBatchInParallel(batch); // 每批内并行
}
```

## 5. 子 Agent 独立 Prompt

每个子 Agent 的 Prompt 只包含当前接口信息：

```
## 当前接口: {interfaceName}
- 端点: {method} {endpoint}
- 文档位置: 第 {index}/{total} 个接口

## 接口文档（仅当前接口部分）
{sectionText}

## 上下文
- 资金方: {providerCode}
- 同文档其他接口: {siblingInterfaces}    ← 仅名称和端点，不传全文

## 数据源字段目录
{fieldCatalog}

## 全局公共字段
{globalContext.commonFields}
```

**关键要点：**
- 每个子 Agent 只看到自己的 `sectionText`，不传全文
- `siblingInterfaces` 只传摘要（名称 + 端点），提供上下文但不污染
- 全局公共字段注入到每个 Prompt 中（签名、时间戳等）

## 6. 全局信息共享

并行处理前提取一次，注入到所有子 Agent：

```java
GlobalContext global = new GlobalContext();
global.setProviderConfig(
    extractProviderConfig(fullDocument)     // 资金方名称、baseUrl
);
global.setCommonFields(
    extractCommonFields(fullDocument)       // 签名字段、时间戳等
);
global.setGlobalEnums(
    extractGlobalEnums(fullDocument)        // 全局枚举值
);

// 每个子 Agent 的 Prompt 都注入 global
prompt = promptBuilder.buildInterfacePrompt(segment, global, fieldCatalog);
```

## 7. 并行 vs 串行选择

| 模式 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| 全并行 | 接口无依赖 | 速度最快 | 占用多个 LLM 连接 |
| 分批并行 | 接口 > 5 个 | 控制并发 | 实现稍复杂 |
| 串行 | 有顺序依赖 | 简单可靠 | 慢 |

**默认：接口数 ≤ 5 时全并行，> 5 时分批并行。**

## 8. Provider 并发安全

所有接口共享同一个 Provider。并行写入前先同步创建 Provider：

```java
// 同步创建 Provider（只一次）
Long providerId = configWriter.getOrCreateProvider(providerConfig, providerCode);

// Provider 创建完成后，Template/Flow/Mappings 并行写入
// Provider 的 findByCode → 复用逻辑本身幂等，并发安全
```

## 9. 单接口失败处理

```java
CompletableFuture<InterfaceResultItem> future = CompletableFuture
    .supplyAsync(() -> processInterface(seg), executor)
    .exceptionally(ex -> {
        // 单个接口失败 → 标记为 FAILED，不影响其他
        return InterfaceResultItem.failed(seg, ex.getMessage());
    })
    .orTimeout(10, TimeUnit.MINUTES)      // 超时不阻塞
    .exceptionally(ex ->
        InterfaceResultItem.timeout(seg)
    );
```

每个接口独立标记状态：`SUCCESS` / `FAILED` / `TIMEOUT`。全部失败时降级为全文档单接口处理。

## 10. 修改文件

```
fundlink-ai-core/
├── AgentLoopOrchestrator.java   # 新增 SPLITTING / PROCESSING_INTERFACES 阶段
├── PromptBuilder.java           # 新增 buildInterfacePrompt()
├── ConfigWriter.java            # writeAll() 新增 interfaceId 参数
├── RequirementResult.java       # 新增 interfaceId/interfaceName/index/totalInterfaces
└── requirement/
    └── MultiInterfaceResult.java  # 新增：多接口结果聚合
```
