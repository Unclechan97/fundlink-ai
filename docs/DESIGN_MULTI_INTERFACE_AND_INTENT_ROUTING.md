# 多接口分割 + 通用意图路由 设计计划 V2

> 2026-08-10 | 基于代码审查的完整后端改造 + 前端交互方案

---

## 一、Context

当前 FundLink AI 的 Copilot 是"单接口文档 → 单套配置"流水线。需要扩展为：

1. **多接口分割**：资金方文档通常包含多个接口（放款申请 + 放款查询 + 还款申请等），需拆分后逐个生成
2. **通用意图路由**：前端输入是通用的，用户可能输入接口文档、业务问题或报错日志，后端需先识别意图再路由

本计划的核心原则：**向后兼容、渐进改造、兜底优先**。

---

## 二、总体架构

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

### 核心原则

- **程序化优先**：拆分以正则/结构匹配为主，LLM 仅做校验和补漏（节省 Token、避免上下文爆炸）
- **子 Agent 并行**：每个接口是独立的 `RequirementAgent.analyze()` 调用，独立 Prompt、独立 LLM 调用、独立写入
- **去重内置**：拆分后进行端点去重，避免同一接口被重复生成
- **兜底优先**：每一步都有降级路径，任何时候都可以退化为单接口处理

---

## 三、DocumentSplitter — 接口拆分（核心改造）

### 3.1 为什么不用 LLM 做拆分

| 维度 | LLM 拆分 | 程序化拆分 |
|------|---------|-----------|
| 上下文消耗 | 整个文档进上下文，接口越多越容易超限 | 不消耗 Token |
| 准确性 | 依赖 Prompt 质量，可能漏拆/误拆 | 确定性规则，边界明确 |
| 成本 | 每次拆分都有 LLM 调用成本 | 零成本 |
| 速度 | 秒级 | 毫秒级 |
| 异常文档 | 能理解非标准格式 | 对非标准格式可能失效 |

**结论：程序化为主，LLM 做校验和兜底。**

### 3.2 程序化拆分策略

按优先级依次尝试，第一个成功即返回：

#### Strategy 1：Markdown 标题分割（优先级最高）

适用于结构化好的文档。检测 `## `、`### ` 级别的标题，匹配接口关键词。

```java
// 伪代码
Pattern INTERFACE_HEADING = Pattern.compile(
    "^#{2,3}\\s*(.+?(?:接口|申请|查询|通知|回调|确认|取消|退款).+)",
    Pattern.MULTILINE
);

// 匹配示例:
// ## 1. 放款申请接口
// ### 2.1 还款查询接口
// ## 放款结果通知接口
```

拆分后每个 Section 的标题作为 `interfaceName`，Section 内容作为 `sectionText`。

#### Strategy 2：分隔线分割（优先级次之）

适用于用 `---`、`***`、`===` 等分隔不同接口的文档。

```java
Pattern SECTION_DELIMITER = Pattern.compile(
    "\n---+\n|\n\\*{3,}\n|\n===+\n|^\\d+\\.[\\s　]+",
    Pattern.MULTILINE
);
```

#### Strategy 3：接口元信息锚点分割（兜底）

从文档中找到所有 `endpoint + method` 组合，以它们为锚点进行切割。

```java
// 匹配常见接口描述模式
Pattern ENDPOINT_PATTERN = Pattern.compile(
    "(?:接口(?:名称|地址|路径|URL)|请求地址|endpoint|API|url)\\s*[：:]\\s*" +
    "(?:POST|GET|PUT|DELETE)?\\s*(/[a-zA-Z0-9_\\-/{}.]+)",
    Pattern.CASE_INSENSITIVE
);
```

找到锚点后，两个锚点之间的内容属于前一个接口。

#### Strategy 4：全文档即单接口（最终兜底）

以上策略都失败 → 整个文档作为一个 `InterfaceSegment`，退化为现有逻辑。

### 3.3 LLM 校验 + 补漏

程序化拆分后，用一次轻量 LLM 调用做校验（不传全文，只传拆分摘要）：

```
校验以下拆分结果是否合理。如果漏了接口，返回补充的接口列表。
如果拆错了（一个接口被拆成多个），返回合并建议。

当前拆分结果：
[
  {"name": "放款申请", "endpoint": "POST /api/loan/apply"},
  {"name": "还款查询", "endpoint": "POST /api/repay/query"}
]

原文档前200字符: {docPreview}

仅输出 JSON：{ "valid": true/false, "issues": [], "suggestions": [] }
```

### 3.4 去重逻辑

拆分完成后，按 `(method, endpoint)` 去重：

```
去重规则（按优先级）：
1. method + endpoint 完全相同 → 保留 sectionText 更长（内容更丰富）的那个
2. endpoint 相同但 method 不同 → 保留两者（GET /api/loan 和 POST /api/loan 是不同的接口）
3. 仅 endpoint 的 path 部分相同，host 不同 → 保留两者（不同环境）
4. interfaceName 高度相似（编辑距离 < 3）→ 可能重复，警告用户确认
```

**去重结果报告给前端：**
```json
{
  "interfaces": [...],
  "deduplications": [
    {
      "kept": {"name": "放款申请", "endpoint": "POST /api/loan/apply"},
      "removed": {"name": "放款申请接口", "endpoint": "POST /api/loan/apply"},
      "reason": "端点重复，保留内容更完整的版本"
    }
  ]
}
```

### 3.5 InterfaceSegment 模型

```java
public class InterfaceSegment {
    String interfaceId;          // 唯一标识：合约名_hash (如 "loanApply_a1b2c3")
    String interfaceName;        // 接口名称
    String endpoint;             // 如 "POST /api/loan/apply"
    String method;               // POST / GET / PUT / DELETE
    String sectionText;          // 该接口的文档原文片段
    String flowType;             // LOAN / CREDIT / REPAY（初步判定，Analyze 阶段可修正）
    int index;                   // 在文档中的序号
    String parentHeading;        // 隶属的顶级标题（如"放款相关接口"）
    
    // 拆分元数据
    SplitSource splitSource;     // MARKDOWN_HEADING / DELIMITER / ANCHOR / FULL_DOC
    double splitConfidence;      // 拆分置信度（程序化=0.95，LLM校验后可能调整）
}
```

---

## 四、并行子 Agent 处理

### 4.1 为什么用子 Agent 模式

当前 `AgentLoopOrchestrator` 串行处理：ANALYZE → VALIDATE → DRYRUN。对于多接口场景：

- **串行问题**：3 个接口 × (ANALYZE + VALIDATE + DRYRUN) = 至少 9 次 LLM 调用，串行耗时 = 累加
- **并行优势**：3 个接口各自的 ANALYZE 互不依赖，可以同时进行
- **隔离优势**：每个接口有自己的 Prompt 上下文，不同接口的字段不会互相污染
- **独立重试**：接口 1 失败不影响接口 2 和 3

### 4.2 并行处理架构

```
InterfaceSegment[] interfaces = splitter.split(documentText);

// Phase 1: 并行 ANALYZE（所有接口同时开始）
List<CompletableFuture<InterfaceResultItem>> futures = interfaces.stream()
    .map(seg -> CompletableFuture.supplyAsync(() -> {
        // 每个接口独立的 Prompt
        RequirementResult result = requirementAgent.analyze(
            seg.getSectionText(),    // 只传该接口的片段
            providerCode,
            seg.getFlowType(),
            previousErrors           // 可能为空
        );
        return InterfaceResultItem.of(seg, result);
    }, interfaceExecutor))  // 专用线程池
    .toList();

// Phase 2: 等待全部完成
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// Phase 3: 对成功的接口，并行写入 ConfigWriter
List<InterfaceResultItem> successItems = ...;
List<CompletableFuture<WriteResult>> writeFutures = successItems.stream()
    .map(item -> CompletableFuture.supplyAsync(() ->
        configWriter.writeAll(item.getResult(), providerCode, item.getFlowType(), item.getInterfaceId()),
        writeExecutor
    ))
    .toList();

// Phase 4: 对写入成功的接口，并行 VALIDATE + DRYRUN
// ...
```

### 4.3 执行策略对比

| 模式 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **全并行** | 接口之间无依赖 | 速度最快 | 同时占用多个 LLM 连接 |
| **分批并行** | 接口数量 > 5 | 控制并发压力 | 实现稍复杂 |
| **串行** | 接口之间有顺序依赖 | 简单可靠 | 慢 |

**默认策略：全并行，最多 5 个并发（可配置）**。当接口数量 > 5 时，分批执行，每批 5 个。

```java
// 配置
fundlink:
  interface:
    max-parallel: 5        # 最大并行接口数
    parallel-timeout-minutes: 10  # 单个接口处理超时

// 分批逻辑
int batchSize = config.getMaxParallel();
List<List<InterfaceSegment>> batches = partition(interfaces, batchSize);
for (List<InterfaceSegment> batch : batches) {
    // 每批并行处理
    processBatchInParallel(batch);
}
```

### 4.4 子 Agent 独立 Prompt

每个子 Agent 的 Prompt 包含：

```
## 当前接口: {interfaceName}
## 接口信息
- 端点: {method} {endpoint}
- 文档位置: 第 {index}/{total} 个接口

## 接口文档（仅当前接口部分）
{sectionText}

## 上下文
- 资金方: {providerCode}
- 同文档其他接口: {siblingInterfaces}  ← 提供相邻接口信息，帮助理解上下文

## 数据源字段目录
{fieldCatalog}  ← 与现有逻辑相同
```

**关键改进**：
- 只传入当前接口的文档片段，不传全文（避免上下文污染）
- 告知有其他接口存在（避免字段命名冲突）
- 独立 Prompt 让 LLM 更聚焦，生成质量更高

### 4.5 全局信息共享

某些信息是所有接口共享的，在并行分析前统一提取：

```java
// 全局提取（只做一次）
GlobalContext global = new GlobalContext();
global.setProviderConfig(extractProviderConfig(fullDocument));  // 资金方名称、baseUrl
global.setCommonFields(extractCommonFields(fullDocument));      // 公共字段（签名、时间戳等）
global.setGlobalEnums(extractGlobalEnums(fullDocument));        // 全局枚举值

// 每个接口的 Prompt 注入 GlobalContext
prompt = buildPrompt(interfaceSegment, global, fieldCatalog);
```

---

## 五、边缘场景与兜底方案

### 场景 1：程序化拆分只识别出 1 个接口

**可能原因**：文档确实是单接口；文档格式不标准，拆分规则没命中。

**兜底**：
1. 如果只有 1 个接口 → 直接交给 `RequirementAgent` 处理（退化为现有逻辑，无额外开销）
2. 如果 LLM 校验发现应该有多个接口 → 降级到 LLM 拆分

### 场景 2：程序化拆分识别出 N 个接口，但 N 太大（> 20）

**可能原因**：文档包含大量 API（如 OpenAPI spec）；拆分规则过度匹配。

**兜底**：
1. 如果 N > 20 → 暂停拆分，返回接口列表让用户确认（前端展示"检测到 35 个接口定义，是否全部处理？"）
2. 用户可以选择部分接口
3. 设置上限 `fundlink.interface.max-count: 50`，超过则拒绝自动处理

### 场景 3：拆分出两个完全相同的接口（端点 + method 相同）

**原因**：文档中同一接口在不同地方被描述（如概览 + 详细定义）。

**兜底**：
1. 去重阶段自动合并，保留内容更丰富的版本
2. 前端提示 "检测到重复接口定义，已合并"
3. 用户可以在前端查看被合并的原始文本

### 场景 4：拆分出名称相似但端点不同的接口

**原因**：如"放款申请（JSON）"和"放款申请（XML）"，或不同版本的接口。

**兜底**：
1. 不去重（端点不同就是不同接口）
2. 在 interfaceName 中保留区分信息
3. 生成不同的 Template Code

### 场景 5：并行处理中某个接口超时/失败

**原因**：LLM 调用超时、解析失败、写入 FundLink 失败。

**兜底**：
1. 单个接口失败不影响其他接口（CompletableFuture 的 exceptionally 处理）
2. 每个接口独立标记状态：SUCCESS / FAILED / TIMEOUT
3. 前端展示每个接口的独立状态
4. 用户可以对失败的接口单独重试
5. 如果全部失败 → 降级为全文档单接口处理（现有逻辑）

### 场景 6：并行写入 ConfigWriter 时 Provider 冲突

**原因**：所有接口共享同一个 Provider，但并行写入时可能同时尝试创建 Provider。

**兜底**：
1. Provider 在并行写入前先单独创建（同步、一次性）
2. Template/Flow/Mappings 在 Provider 创建完成后并行写入
3. Provider 的 `findByCode → 复用` 逻辑本身是幂等的，即使并发也不会有问题

### 场景 7：文档中包含接口依赖关系

**原因**：接口 A 的响应字段是接口 B 的请求字段（如贷款申请返回 loanNo，贷款查询需要 loanNo）。

**兜底**：
1. 拆分时不考虑依赖（每个接口独立处理）
2. 依赖关系在后续"流程拼接"阶段处理（未来功能）
3. 当前版本：每个接口生成独立 Flow，用户手动关联

### 场景 8：意图识别将接口文档误判为知识问答

**原因**：文档措辞偏向说明性，或 LLM 判断失误。

**兜底**：
1. 前端展示意图识别结果 + 置信度
2. 用户可一键切换意图（前端按钮：不是接口文档？切换为知识问答 / 问题排查）
3. 切换后重新走对应 Handler
4. 低置信度（< 0.7）时前端弹确认框

### 场景 9：用户粘贴的是非标准格式（PDF 复制、OCR 文本）

**原因**：格式混乱，没有清晰的结构。

**兜底**：
1. 程序化拆分失败 → LLM 拆分（LLM 对非标准格式有更好理解能力）
2. LLM 拆分也失败 → 全文档作为单接口处理
3. 前端提示"未能识别接口结构，将按单接口处理"

### 场景 10：接口拆分正确但字段映射质量差

**原因**：文档片段信息不足（缺少上下文字段说明）。

**兜底**：
1. 在子 Agent Prompt 中加入 GlobalContext（公共字段、枚举值）
2. 低置信度映射（confidence < 0.5）高亮标记
3. 前端手动模式下用户可编辑

---

## 六、并行 vs 串行决策树

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
         (现有逻辑)    (5个线程)    ┌────┴────┐
                                   ▼         ▼
                              用户选部分   用户确认全部
                              → 并行      → 分批并行
                                           (每批5个)
```

---

## 七、去重策略详细设计

```java
public class InterfaceDeduplicator {
    
    public DedupResult deduplicate(List<InterfaceSegment> segments) {
        // Round 1: 精确去重（method + endpoint 完全相同）
        Map<String, List<InterfaceSegment>> byEndpoint = segments.stream()
            .collect(Collectors.groupingBy(s -> normalizeEndpoint(s.getMethod(), s.getEndpoint())));
        
        List<InterfaceSegment> kept = new ArrayList<>();
        List<DedupRecord> removed = new ArrayList<>();
        
        for (var entry : byEndpoint.entrySet()) {
            List<InterfaceSegment> group = entry.getValue();
            if (group.size() == 1) {
                kept.add(group.get(0));
            } else {
                // 保留 sectionText 最长的
                InterfaceSegment best = group.stream()
                    .max(Comparator.comparingInt(s -> s.getSectionText().length()))
                    .get();
                kept.add(best);
                for (InterfaceSegment other : group) {
                    if (other != best) {
                        removed.add(new DedupRecord(best, other, "端点重复，保留内容更丰富的版本"));
                    }
                }
            }
        }
        
        // Round 2: 名称相似度检测（仅告警，不去重）
        List<SimilarityWarning> warnings = detectSimilarNames(kept);
        
        return new DedupResult(kept, removed, warnings);
    }
    
    private String normalizeEndpoint(String method, String endpoint) {
        // 去掉尾部斜杠、query string、统一大小写
        return (method + " " + endpoint).trim()
            .replaceAll("/+$", "")
            .replaceAll("\\?.*$", "")
            .toUpperCase();
    }
}
```

---

## 八、后端 API 设计

### 新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/split` | 文档拆分（程序化 + LLM 校验），返回接口列表 + 去重报告 |
| POST | `/api/ai/intent` | 意图识别 |
| POST | `/api/ai/qa` | 知识问答（架子） |
| POST | `/api/ai/troubleshoot` | 问题排查（架子） |
| POST | `/api/ai/interfaces/{interfaceId}/retry` | 对单个失败接口重新处理 |

### 修改 API

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/ai/analyze` | 自动调用 split → 并行处理 → 聚合返回 |
| POST | `/api/ai/loop` | 新增 `interfaceIds` 参数；支持多接口并行闭环 |

### Split API 详细设计

```
POST /api/ai/split
Request:  { documentText, providerCode }
Response: {
  code: 0,
  data: {
    totalCount: 3,
    interfaces: [
      {
        interfaceId: "loanApply_a1b2c3",
        interfaceName: "放款申请",
        endpoint: "POST /api/loan/apply",
        method: "POST",
        flowType: "LOAN",
        splitConfidence: 0.95,
        splitSource: "MARKDOWN_HEADING",
        sectionPreview: "## 1. 放款申请接口\n\n### 请求参数\n\n| 字段名 | 类型 | 必填 |..."
      },
      ...
    ],
    deduplications: [
      { kept: "放款申请", removed: "放款申请接口", reason: "端点重复" }
    ],
    warnings: [
      { message: "接口 '放款申请' 和 '放款复核' 名称相似，请确认是否为同一接口" }
    ]
  }
}
```

### Analyze API 改造

```
POST /api/ai/analyze
Request:  { documentText, providerCode, flowType, selectedInterfaceIds? }
Response: {
  code: 0,
  data: {
    providerCode: "CMB",
    totalCount: 3,
    successCount: 2,
    failedCount: 1,
    interfaces: [
      {
        interfaceId: "loanApply_a1b2c3",
        interfaceName: "放款申请",
        status: "SUCCESS",
        result: { /* RequirementResult */ }
      },
      {
        interfaceId: "loanQuery_b2c3d4",
        interfaceName: "放款查询",
        status: "FAILED",
        errorMessage: "LLM 解析失败: 缺少接口字段定义",
        result: null
      },
      ...
    ]
  }
}
```

---

## 九、SSE 事件扩展（多接口）

### 新增事件

| 事件名 | 数据 | 说明 |
|--------|------|------|
| `intent:result` | `{intent, confidence}` | 意图识别结果 |
| `split:start` | `{}` | 开始拆分 |
| `split:complete` | `{totalCount, interfaces: [{interfaceId, name, endpoint}]}` | 拆分完成 |
| `split:dedup` | `{kept, removed, reason}` | 去重报告 |
| `interface:start` | `{interfaceId, name, index, total}` | 开始处理某接口 |
| `interface:phase:start` | `{interfaceId, phase, round, maxRounds}` | 某接口的阶段开始 |
| `interface:phase:progress` | `{interfaceId, phase, message}` | 某接口的阶段进度 |
| `interface:phase:complete` | `{interfaceId, phase, summary}` | 某接口的阶段完成 |
| `interface:phase:error` | `{interfaceId, phase, message}` | 某接口的阶段错误 |
| `interface:complete` | `{interfaceId, name, status, summary}` | 某接口处理完成 |
| `interface:skipped` | `{interfaceId, name, reason}` | 某接口被跳过 |
| `all:complete` | `{totalCount, successCount, failedCount}` | 全部接口处理完成 |

### 事件流示例

```
split:start
split:complete     → { totalCount: 3, interfaces: [...] }
interface:start    → { interfaceId: "loanApply", name: "放款申请", index: 0, total: 3 }
interface:start    → { interfaceId: "loanQuery", name: "放款查询", index: 1, total: 3 }
interface:start    → { interfaceId: "repayApply", name: "还款申请", index: 2, total: 3 }
interface:phase:start  → { interfaceId: "loanApply", phase: "ANALYZE", ... }
interface:phase:start  → { interfaceId: "loanQuery", phase: "ANALYZE", ... }
  ← 三个接口并行处理，事件交错推送
interface:phase:complete → { interfaceId: "loanQuery", phase: "ANALYZE" }
interface:complete → { interfaceId: "loanQuery", status: "SUCCESS" }
interface:phase:error → { interfaceId: "repayApply", phase: "ANALYZE" }
interface:complete → { interfaceId: "repayApply", status: "FAILED" }
interface:phase:complete → { interfaceId: "loanApply", phase: "ANALYZE" }
interface:complete → { interfaceId: "loanApply", status: "SUCCESS" }
all:complete       → { totalCount: 3, successCount: 2, failedCount: 1 }
```

前端根据 `interfaceId` 过滤事件，每个接口卡片只显示自己的事件。

---

## 十、前端交互设计

### 10.1 通用输入区

```
┌─────────────────────────────────────────────────────────┐
│ 🤖 AI Copilot                           [手动/自动]     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  资金方编码: [CMB________]  [🔍 自动识别]               │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │                                                 │    │
│  │   粘贴接口文档 / 输入业务问题 / 贴入报错日志       │    │
│  │                                                 │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  [📄 上传文档]  [发送]                                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 10.2 意图识别 + 接口拆分确认

```
┌─────────────────────────────────────────────────────────┐
│ 🔍 AI 识别结果                                          │
│                                                         │
│ ┌───────────────────────────────────────────────────┐   │
│ │ 意图: 接口开发          置信度: 95%                │   │
│ │ → 检测到 3 个接口定义                              │   │
│ └───────────────────────────────────────────────────┘   │
│                                                         │
│ 检测到的接口:                            [全选] [取消]  │
│ ┌───────────────────────────────────────────────────┐   │
│ │ ☑ 1. 放款申请     POST /api/loan/apply    LOAN   │   │
│ │ ☑ 2. 放款查询     POST /api/loan/query    LOAN   │   │
│ │ ☑ 3. 还款申请     POST /api/repay/apply   REPAY  │   │
│ └───────────────────────────────────────────────────┘   │
│                                                         │
│ ⚠ 去重提示: "放款申请"和"放款申请接口"已合并             │
│                                                         │
│ [不是接口文档？切换为: 知识问答 | 问题排查]               │
│                                                        │
│ [手动逐个处理]  [自动闭环全部]                           │
└─────────────────────────────────────────────────────────┘
```

### 10.3 并行处理进度（自动模式）

```
┌─────────────────────────────────────────────────────────┐
│ 🔄 自动闭环    Task: LOOP-A1B2C3D4    3 个接口并行中    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 📋 放款申请   │  │ 📋 放款查询   │  │ 📋 还款申请   │   │
│  │ ✅ 已完成     │  │ 🔄 处理中     │  │ ⏳ 等待中     │   │
│  │              │  │              │  │              │   │
│  │ ANALYZE ✓   │  │ ANALYZE ✓   │  │ ANALYZE ⏳  │   │
│  │ VALIDATE ✓  │  │ VALIDATE 🔄 │  │ VALIDATE ⏳  │   │
│  │ DRYRUN ✓    │  │ DRYRUN ⏳   │  │ DRYRUN ⏳   │   │
│  │ 写入 ✓      │  │              │  │              │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 🔴 还款申请 - ANALYZE 失败: LLM 解析错误          │   │
│  │ [重试此接口]  [跳过]  [编辑后重试]                 │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  [中断全部任务]                                         │
└─────────────────────────────────────────────────────────┘
```

### 10.4 手动模式多接口展示

```
┌─────────────────────────────────────────────────────────┐
│ 接口列表    [全部解析]  [全部采纳]  [写入 FundLink]      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ ▼ 1. 放款申请  POST /api/loan/apply  [✅ 已解析]       │
│   ┌─ 字段映射表（复用手动模式现有 UI）─┐               │
│   └─ 流程图（ReactFlow）──────────────┘               │
│                                                         │
│ ▼ 2. 放款查询  POST /api/loan/query  [⚠ 部分字段无匹配] │
│   ┌─ 字段映射表───────────────────────┐               │
│   └─ 流程图──────────────────────────┘               │
│                                                         │
│ ▶ 3. 还款申请  POST /api/repay/apply  [⏳ 待解析]       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 10.5 知识问答 / 问题排查 UI（架子）

```
┌─────────────────────────────────────────────────────────┐
│ 🔍 识别结果: 知识问答          置信度: 88%               │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ 💬 用户: 放款流程中什么是T+1对账？                        │
│                                                         │
│ 🤖 AI: T+1对账是指放款日次日（T+1），资金方将放款结果     │
│    回传平台，平台与资金方进行逐笔对账的流程...            │
│                                                         │
│ [不是知识问答？切换为: 接口开发 | 问题排查]                │
│ ┌─────────────────────────────────────────────────┐     │
│ │ 输入新问题...                               [发送] │     │
│ └─────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
```

---

## 十一、IntentRouter 详细设计

### 11.1 IntentType 枚举

```java
public enum IntentType {
    INTERFACE_DEV("接口开发", "检测到 API 接口文档，将进入接口配置生成流程"),
    KNOWLEDGE_QA("知识问答", "检测到业务问题，将进入知识问答模式"),
    TROUBLESHOOTING("问题排查", "检测到报错信息，将进入故障排查模式"),
    UNKNOWN("未知", "无法识别意图，请手动选择");
}
```

### 11.2 IntentRouter 实现

```java
@Component
public class IntentRouter {
    
    private final Map<IntentType, IntentHandler> handlers = new EnumMap<>(IntentType.class);
    private final LlmGateway llmGateway;
    
    public IntentRouter(LlmGateway llmGateway,
                        InterfaceDevHandler interfaceDev,
                        KnowledgeQaHandler knowledgeQa,
                        TroubleshootingHandler troubleshooting) {
        this.llmGateway = llmGateway;
        handlers.put(IntentType.INTERFACE_DEV, interfaceDev);
        handlers.put(IntentType.KNOWLEDGE_QA, knowledgeQa);
        handlers.put(IntentType.TROUBLESHOOTING, troubleshooting);
    }
    
    public IntentResult route(String userInput, Map<String, Object> context) {
        // Step 1: 快速规则预判（不调 LLM）
        IntentType quickGuess = quickRuleCheck(userInput);
        if (quickGuess != null && quickGuess != IntentType.UNKNOWN) {
            // 高置信度规则命中 → 跳过 LLM
            return IntentResult.of(quickGuess, 0.95, "规则匹配");
        }
        
        // Step 2: LLM 意图识别
        String prompt = buildIntentPrompt(userInput);
        LlmResponse resp = llmGateway.chat(
            LlmRequest.ofTask("intent", prompt, context.get("traceId").toString())
        );
        return parseIntentResult(resp.getContent());
    }
    
    private IntentType quickRuleCheck(String input) {
        // 强特征直接判断，不用 LLM
        if (input.contains("Exception") || input.contains("at com.") 
            || input.contains("Caused by:") || input.contains("Stack trace:")) {
            return IntentType.TROUBLESHOOTING;
        }
        // endpoint 特征
        if (input.matches("(?s).*(?:POST|GET|PUT|DELETE)\\s+/[a-zA-Z].*")) {
            return IntentType.INTERFACE_DEV;
        }
        // 请求参数表格特征
        if (input.contains("请求参数") || input.contains("响应参数") 
            || input.contains("接口名称") || input.contains("字段名")) {
            return IntentType.INTERFACE_DEV;
        }
        // 没有强特征 → 走 LLM
        return null;
    }
}
```

---

## 十二、文件变更清单

### 新增文件 (18 个)

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/
├── intent/
│   ├── IntentRouter.java              # 意图路由（含快速规则 + LLM）
│   ├── IntentType.java                # 意图枚举
│   ├── IntentResult.java              # 识别结果
│   ├── IntentHandler.java             # 策略接口
│   ├── IntentContext.java             # 上下文
│   ├── InterfaceDevHandler.java       # 接口开发处理器
│   ├── KnowledgeQaHandler.java        # 知识问答处理器（架子）
│   └── TroubleshootingHandler.java    # 问题排查处理器（架子）
├── split/
│   ├── DocumentSplitter.java          # 文档拆分器（程序化策略链）
│   ├── SplitStrategy.java             # 拆分策略接口
│   ├── MarkdownHeadingStrategy.java   # 策略1：Markdown 标题
│   ├── DelimiterStrategy.java         # 策略2：分隔线
│   ├── AnchorStrategy.java            # 策略3：端点锚点
│   ├── LlmVerifyStrategy.java         # LLM 校验 + 补漏
│   ├── InterfaceSegment.java          # 接口片段模型
│   └── InterfaceDeduplicator.java     # 去重器
└── requirement/
    └── MultiInterfaceResult.java      # 多接口结果聚合
```

### 修改文件 (10 个)

```
fundlink-ai-core/
├── RequirementAgentImpl.java          # analyze() 不变（接口片段由上层传入）
├── RequirementResult.java             # 新增 interfaceId/interfaceName/index/totalInterfaces
├── ConfigWriter.java                  # writeAll() 新增 interfaceId 参数
├── AgentLoopOrchestrator.java         # 新增 SPLITTING/PROCESSING_INTERFACES；并行接口处理
├── PromptBuilder.java                 # 新增 split prompt / intent prompt / interface prompt
├── LoopTracer.java                    # trace 增加 interfaceId
fundlink-ai-app/
├── CopilotController.java             # 新增 /split /intent /qa /troubleshoot 端点
├── LoopController.java                # loop 端点适配多接口
├── SseLoopEventPublisher.java         # 新增 split/interface 事件类型
fundlink-ui/src/
├── pages/ai/Copilot.jsx               # 通用输入 + 意图展示 + 多接口管理
├── pages/ai/AutoLoopPanel.jsx         # 多接口并行进度 + SSE 事件处理
├── api/index.js                       # 新增 API 调用
```

---

## 十三、实施步骤

### Phase 1：拆分器 + 去重（纯后端，无侵入）
1. 实现 4 个 `SplitStrategy`（程序化拆分）
2. 实现 `InterfaceDeduplicator`（去重）
3. 实现 `DocumentSplitter`（策略链编排 + LLM 校验）
4. 单测覆盖 10 个边缘场景

### Phase 2：意图路由
1. `IntentRouter` + 快速规则 + LLM 识别
2. `InterfaceDevHandler` 委托现有逻辑
3. `KnowledgeQaHandler` / `TroubleshootingHandler` 架子
4. `/api/ai/intent` / `/api/ai/split` 端点

### Phase 3：并行处理引擎
1. `MultiInterfaceResult` 模型
2. `AgentLoopOrchestrator` 并行接口处理逻辑
3. `ConfigWriter` interfaceId 适配
4. `SseLoopEventPublisher` 事件扩展

### Phase 4：前端改造
1. 通用输入 + 意图结果展示 + 接口列表选择
2. 多接口并行进度展示（卡片式）
3. 失败接口单独重试 UI
4. 知识问答 / 问题排查基础 UI

### Phase 5：集成测试
1. 单接口回归
2. 多接口端到端（2/5/10/20个接口）
3. 并行处理超时 / 部分失败
4. 去重 / 格式异常 / 意图误判切换

---

## 十四、验证方式

| 场景 | 输入 | 预期 |
|------|------|------|
| 单接口回归 | 现有单接口文档 | 行为与改造前一致 |
| 多接口 Markdown | 含 3 个 `## 接口` 标题的文档 | 拆出 3 个，并行处理，全部成功 |
| 多接口分隔线 | 用 `---` 分隔 5 个接口 | 拆出 5 个，分两批并行（每批 5 个以内） |
| 重复接口 | 同一接口在文档中出现两次 | 去重后只保留内容更丰富的版本 |
| 非标准格式 | 纯文本无标题无分隔线 | 程序化拆分失败 → LLM 拆分 → 兜底为单接口 |
| 部分失败 | 3 个接口中 1 个 LLM 返回空 | 2 个成功 + 1 个失败，用户可重试失败接口 |
| 意图误判 | 贴入接口文档但 LLM 判断为 QA | 前端展示结果，用户手动切换为接口开发 |
| 超大文档 | 含 25 个接口 | 前端提示数量，用户选择部分后分批并行处理 |
| 中断恢复 | 处理到一半用户点取消 | 已完成的保留，未完成的放弃，不清除已完成结果 |
| 知识问答 | 输入 "什么是放款流程" | 路由到 KnowledgeQaHandler，LLM 直接回答 |
