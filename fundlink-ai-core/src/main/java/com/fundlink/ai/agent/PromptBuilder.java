package com.fundlink.ai.agent;

import com.fundlink.ai.agent.split.InterfaceSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PromptBuilder {

    private Map<String, Map<String, List<String>>> catalog;

    @PostConstruct
    void init() {
        try {
            var yml = new ClassPathResource("field-catalog.yml");
            String content = new String(yml.getInputStream().readAllBytes());
            catalog = parseYaml(content);
            log.info("[PROMPT] Catalog loaded: {} flow types", catalog.size());
        } catch (Exception e) {
            log.error("Failed to load field-catalog.yml", e);
            catalog = Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, List<String>>> parseYaml(String yaml) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        String currentFlow = null, currentSource = null;
        for (String line : yaml.split("\n")) {
            if (line.matches("^[a-z]+:")) {
                currentFlow = line.replace(":", "").trim();
                result.put(currentFlow, new LinkedHashMap<>());
            } else if (line.matches("^  [a-zA-Z]+:")) {
                currentSource = line.replace(":", "").trim();
                result.get(currentFlow).put(currentSource, new ArrayList<>());
            } else if (line.matches("^    - .+")) {
                String field = line.replaceFirst("    - ", "").split("#")[0].trim();
                result.get(currentFlow).get(currentSource).add(field);
            }
        }
        return result;
    }

    public Map<String, List<String>> getFields(String flowType) {
        if (catalog == null) return Map.of();
        return catalog.getOrDefault(flowType.toLowerCase(),
                catalog.getOrDefault("loan", Map.of()));
    }

    public String build(String documentText, String providerCode, String flowType,
                         List<String> ragExamples) {
        var fields = getFields(flowType);
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT);
        sb.append("\n");
        sb.append(buildFieldContext(fields));
        if (ragExamples != null && !ragExamples.isEmpty()) {
            sb.append("\n## 参考案例\n");
            for (String e : ragExamples) sb.append(e).append("\n");
        }
        sb.append("\n## 接口文档\n").append(documentText);
        sb.append("\n## 资金方: ").append(providerCode).append("\n");
        return sb.toString();
    }

    private String buildFieldContext(Map<String, List<String>> fields) {
        StringBuilder sb = new StringBuilder("## 可用数据源字段\n");
        for (var e : fields.entrySet()) {
            String prefix = switch (e.getKey()) {
                case "riskData" -> "riskData"; case "paymentData" -> "paymentData";
                case "loanInfo" -> "loanInfo"; default -> "userInfo";
            };
            sb.append("- ").append(e.getKey()).append(": ");
            sb.append(e.getValue().stream().map(f -> prefix + "." + f)
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }
        sb.append("\n## 数据源 (按顺序调用)\n");
        sb.append("- RISK→riskData  CORE→userInfo  PAYMENT→paymentData\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 3: 子 Agent 独立 Prompt
    // ═══════════════════════════════════════════════════════════

    /**
     * 为单个接口构建独立 Prompt。
     * 只传当前接口的 sectionText，兄弟接口只传摘要。
     */
    public String buildInterfacePrompt(InterfaceSegment segment, List<InterfaceSegment> siblings,
                                        String flowType, String providerCode) {
        var fields = getFields(flowType != null ? flowType : "LOAN");
        int total = siblings != null ? siblings.size() + 1 : 1;
        int index = segment.getIndex() + 1; // 1-based for display

        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT);
        sb.append("\n");
        sb.append(buildFieldContext(fields));

        // 兄弟接口摘要（仅名称 + 端点）
        if (siblings != null && !siblings.isEmpty()) {
            sb.append("\n## 同文档其他接口\n");
            for (InterfaceSegment sib : siblings) {
                sb.append("- ").append(sib.getInterfaceName());
                if (sib.getEndpoint() != null && !sib.getEndpoint().isBlank()) {
                    sb.append(" (").append(sib.getEndpoint()).append(")");
                }
                sb.append("\n");
            }
        }

        // 当前接口信息
        sb.append("\n## 当前接口: ").append(segment.getInterfaceName()).append("\n");
        sb.append("- 端点: ").append(segment.getMethod()).append(" ").append(segment.getEndpoint()).append("\n");
        sb.append("- 文档位置: 第 ").append(index).append("/").append(total).append(" 个接口\n");

        // 当前接口文档片段
        sb.append("\n## 接口文档（仅当前接口部分）\n");
        sb.append(segment.getSectionText());

        // 上下文
        sb.append("\n## 上下文\n");
        sb.append("- 资金方: ").append(providerCode != null ? providerCode : "UNKNOWN").append("\n");
        sb.append("- 流程类型: ").append(flowType != null ? flowType : "LOAN").append("\n");

        return sb.toString();
    }

    static final String SYSTEM_PROMPT = """
你是资金接入系统配置专家。严格按JSON Schema输出配置，不要输出额外内容。

## JSON Schema
```json
{
  "flow_type": "LOAN|CREDIT|REPAY",
  "provider_config": {
    "providerName":"资金方名称(从文档提取)",
    "baseUrl":"接口基础URL(从文档提取)"
  },
  "interface_schema": {
    "endpoint":"POST /api/loan",
    "method":"POST",
    "fields":[{"name":"","type":"String","required":true,"description":""}]
  },
  "field_mappings": [
    {"fund_field":"custName","source_path":"userInfo.realName","transform":"formatAmount","confidence":0.95,"remark":"TODO说明(可选)"},
    {"fund_field":"repayAccount.bankCode","source_path":"paymentData.accountNo","transform":null,"confidence":0.90,"remark":null},
    {"fund_field":"repayAccount.bankName","source_path":"paymentData.bankName","transform":null,"confidence":0.95,"remark":null}
  ],
  "flow_dsl": {
    "nodes":[
      {"id":"n1","type":"START","data":{"label":"开始"}},
      {"id":"n2","type":"DATA_COLLECT","data":{"label":"获取风控","config":{"dataSourceCode":"RISK","outputKey":"riskData"}}},
      {"id":"n3","type":"DATA_COLLECT","data":{"label":"获取客户","config":{"dataSourceCode":"CORE","outputKey":"userInfo"}}},
      {"id":"n4","type":"TEMPLATE_RENDER","data":{"label":"渲染报文","config":{"templateCode":"LOAN_REQ","outputKey":"reqMsg"}}},
      {"id":"n5","type":"SEND_TO_FUND","data":{"label":"发送资金方","config":{"url":"http://fund/api/loan","requestKey":"reqMsg","responseKey":"fundResp"}}},
      {"id":"n6","type":"END","data":{"label":"结束"}}
    ],
    "edges":[
      {"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},
      {"id":"e3","source":"n3","target":"n4"},{"id":"e4","source":"n4","target":"n5"},
      {"id":"e5","source":"n5","target":"n6"}
    ]
  }
}
```

## 规则
### flow_type
- 根据接口文档内容自动判断流程类型:
  - 文档涉及放款/借款/提款/支用 → LOAN
  - 文档涉及授信/额度/征信 → CREDIT
  - 文档涉及还款申请/主动还款/代扣/扣款 → REPAY
  - 无明显特征时默认 LOAN

### provider_config
- providerName: 从文档标题或概述中提取资金方名称
- baseUrl: 从文档中的请求地址提取基础URL(如只到/api，不含具体路径)

### field_mappings
- **完整性要求**: interface_schema.fields 中列出的每一个字段都必须有一条 field_mappings 记录，field_mappings 数量必须等于 fields 数量，一个都不能少
- 按语义推断: 姓名→userInfo.realName, 金额→loanInfo.amount, 手机→userInfo.mobile, 证件号→userInfo.idNo/idType, 银行卡→paymentData.*
- 只有两个transform: formatAmount(金额字段,如applyAmount/amount), nowDate(日期字段)
- 大部分字段transform为null,表示直接透传
- **数据源字段必须来自目录**: source_path 的每一级（含嵌套字段）都必须在上方 ## 可用数据源字段 列表中能找到。严禁编造 repayData、feeDetail、repayType、repayAmount 等目录中不存在的路径。找不到完全匹配的字段时 source_path 输出 ""，走 TODO 占位
- **嵌套对象展开**: 接口文档中的嵌套对象(如 repayAccount 包含 bankCode/bankName 等子字段)，必须在 field_mappings 中逐一展开为独立映射，fund_field 用点号连接: "repayAccount.bankCode"、"repayAccount.bankName"……严禁将整个对象映射为一个字段(如 fund_field="repayAccount", source_path="paymentData")
- **数组路径**: 文档中的数组字段展开时用 [] 标记，如 repayPeriods[].startDate
- **找不到匹配内部数据源的字段**: source_path 输出空字符串 ""，remark 标注 "TODO:需人工确认映射"，严禁跳过该字段或编造 source_path
- confidence: 精确匹配=0.95, 语义推断=0.85, 模糊=0.70, 无匹配(sourcePath为空)=0.0
- fund_field必须来自接口文档字段名

### flow_dsl
- 条件分支示例(注意conditionExpr在出边上,不在节点上):
  CONDITION节点: {"id":"nc","type":"CONDITION","data":{"label":"风控判断"}}
  出边1(满足条件): {"id":"ec1","source":"nc","target":"n_ok","label":"A级","conditionExpr":"#root.riskData.level == 'A'"}
  出边2(否则): {"id":"ec2","source":"nc","target":"n_review","label":"非A级"}
- 文档有"业务流程"则严格遵循,无则默认: START→DATA_COLLECT→TEMPLATE_RENDER→SEND_TO_FUND→END
- SEND_TO_FUND的url取provider_config.baseUrl+接口路径
- dataSourceCode: RISK/CORE/PAYMENT | outputKey: riskData/userInfo/paymentData
""";
}
