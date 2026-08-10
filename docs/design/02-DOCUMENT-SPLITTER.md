# 02 — 文档拆分器（DocumentSplitter）

> 优先级: P0（核心功能，所有多接口场景的入口）
> 依赖: 无（纯程序化逻辑 + 可选 LLM 校验）

---

## 1. 目标

将一份包含多个接口的资金方文档拆分为独立片段，每个片段对应一个接口的完整描述。

## 2. 设计决策：程序化优先，LLM 仅做校验

| 维度 | LLM 拆分 | 程序化拆分 |
|------|---------|-----------|
| 上下文消耗 | 全文进上下文，接口越多越易超限 | **不消耗 Token** |
| 准确性 | 依赖 Prompt 质量 | **确定性规则** |
| 成本 | 每次有 LLM 调用成本 | **零成本** |
| 速度 | 秒级 | **毫秒级** |
| 异常文档 | 能理解非标准格式 | 可能失效 |

**策略链：4 级降级，第一个成功即返回。**

## 3. 拆分策略链

```
DocumentSplitter.split(documentText)
  │
  ├─ Strategy 1: MarkdownHeadingStrategy   ← 标题匹配
  │   ↓ 失败（拆出 0 个或 1 个）
  ├─ Strategy 2: DelimiterStrategy         ← 分隔线匹配
  │   ↓ 失败
  ├─ Strategy 3: AnchorStrategy            ← 端点锚点匹配
  │   ↓ 失败
  └─ Strategy 4: FullDocStrategy           ← 全文档 = 单接口（兜底）
      ↓
  LLM 校验（可选，轻量）
      ↓
  InterfaceDeduplicator（去重）
      ↓
  List<InterfaceSegment>
```

### Strategy 1：Markdown 标题匹配

正则匹配 `## `、`### ` 级别的标题中包含接口关键词：

```java
Pattern INTERFACE_HEADING = Pattern.compile(
    "^#{2,3}\\s*(.+?(?:接口|申请|查询|通知|回调|确认|取消|退款).+)",
    Pattern.MULTILINE
);
```

以标题为分界点切分文档，每个 Section 的标题作为 `interfaceName`。

### Strategy 2：分隔线匹配

匹配文档中的水平分隔线，如 `---`、`***`、`===`，以及中文数字序号：

```java
Pattern SECTION_DELIMITER = Pattern.compile(
    "\n---+\n|\n\\*{3,}\n|\n===+\n|^\\d+\\.[\\s　]+",
    Pattern.MULTILINE
);
```

### Strategy 3：端点锚点匹配

以接口 URL 定义语句为锚点进行切分，两个锚点之间的内容属于前一个接口：

```java
Pattern ENDPOINT_PATTERN = Pattern.compile(
    "(?:接口(?:名称|地址|路径|URL)|请求地址|endpoint|API|url)\\s*[：:]\\s*" +
    "(?:POST|GET|PUT|DELETE)?\\s*(/[a-zA-Z0-9_\\-/{}.]+)",
    Pattern.CASE_INSENSITIVE
);
```

### Strategy 4：全文档兜底

以上策略均失败时，整个文档作为一个 `InterfaceSegment`，**退化为现有单接口逻辑**。

## 4. 策略接口

```java
public interface SplitStrategy {
    /** 优先级，数字越小越先尝试 */
    int priority();

    /** 尝试拆分，返回 null 或空列表表示本策略无法处理 */
    List<InterfaceSegment> trySplit(String documentText);
}
```

每个策略实现 `trySplit()`：成功返回 segments 列表，失败返回空列表。`DocumentSplitter` 按 priority 排序后依次尝试。

## 5. LLM 校验（可选、轻量）

程序化拆分后，只传**拆分摘要**（不传全文）给 LLM 做快速校验：

```
校验以下拆分结果。如果漏了接口，返回补充列表。如果拆错了，返回合并建议。

当前拆分: [
  {"name": "放款申请", "endpoint": "POST /api/loan/apply"},
  {"name": "还款查询", "endpoint": "POST /api/repay/query"}
]

原文档前 200 字符: {docPreview}

仅输出 JSON: { "valid": true/false, "issues": [], "suggestions": [] }
```

**关键：不传全文，Token 消耗极小。**

## 6. 去重（InterfaceDeduplicator）

拆分完成后，按 `(method, endpoint)` 去重：

| 优先级 | 规则 | 动作 |
|--------|------|------|
| 1 | method + endpoint 完全相同 | 保留 sectionText 更长的 |
| 2 | endpoint 相同，method 不同 | 保留两者（不同接口） |
| 3 | endpoint path 相同，host 不同 | 保留两者（不同环境） |
| 4 | interfaceName 编辑距离 < 3 | **仅告警**，不去重 |

```java
public class InterfaceDeduplicator {
    public DedupResult deduplicate(List<InterfaceSegment> segments) {
        // Round 1: 精确去重
        Map<String, List<InterfaceSegment>> byEndpoint = segments.stream()
            .collect(groupingBy(s -> normalize(s.getMethod(), s.getEndpoint())));

        // Round 2: 名称相似度检测（仅告警）
        List<SimilarityWarning> warnings = detectSimilarNames(kept);

        return new DedupResult(kept, removed, warnings);
    }

    private String normalize(String method, String endpoint) {
        return (method + " " + endpoint).trim()
            .replaceAll("/+$", "").replaceAll("\\?.*$", "").toUpperCase();
    }
}
```

去重结果透明报告给前端：

```json
{
  "deduplications": [
    {
      "kept": {"name": "放款申请", "endpoint": "POST /api/loan/apply"},
      "removed": {"name": "放款申请接口", "endpoint": "POST /api/loan/apply"},
      "reason": "端点重复，保留内容更丰富的版本"
    }
  ],
  "warnings": [
    {"message": "接口 '放款申请' 和 '放款复核' 名称相似，请确认"}
  ]
}
```

## 7. InterfaceSegment 模型

```java
public class InterfaceSegment {
    String interfaceId;          // "loanApply_a1b2c3"（名称_hash）
    String interfaceName;        // "放款申请"
    String endpoint;             // "POST /api/loan/apply"
    String method;               // POST
    String sectionText;          // 该接口的文档原文片段
    String flowType;             // 初步判定，Analyze 阶段可修正
    int index;                   // 在文档中的序号
    String parentHeading;        // 隶属的顶级标题
    SplitSource splitSource;     // MARKDOWN_HEADING / DELIMITER / ANCHOR / FULL_DOC
    double splitConfidence;      // 程序化=0.95，LLM 校验后可能调整
}

enum SplitSource { MARKDOWN_HEADING, DELIMITER, ANCHOR, FULL_DOC }
```

## 8. 新增文件

```
fundlink-ai-core/src/main/java/com/fundlink/ai/agent/split/
├── DocumentSplitter.java
├── SplitStrategy.java
├── MarkdownHeadingStrategy.java
├── DelimiterStrategy.java
├── AnchorStrategy.java
├── LlmVerifyStrategy.java
├── InterfaceSegment.java
└── InterfaceDeduplicator.java
```
