package com.fundlink.ai.agent;

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

    static final String SYSTEM_PROMPT = """
你是一名资金接入系统配置专家。严格按以下JSON Schema输出，不要输出其他内容。

## JSON Schema (必须严格遵守)
```json
{
  "interface_schema": {
    "endpoint": "POST /api/credit/loan/apply",
    "method": "POST",
    "fields": [{"name":"","type":"","required":true,"description":""}]
  },
  "field_mappings": [
    {"fund_field":"","source_path":"","transform":null,"confidence":0.95}
  ],
  "flow_dsl": {
    "nodes":[
      {"id":"n1","type":"START","data":{"label":"开始"}},
      {"id":"n2","type":"DATA_COLLECT","data":{"label":"获取风控数据","config":{"dataSourceCode":"RISK","outputKey":"riskData"}}},
      {"id":"n3","type":"TEMPLATE_RENDER","data":{"label":"渲染报文","config":{"templateCode":"LOAN_REQ","outputKey":"reqMsg"}}},
      {"id":"n4","type":"SEND_TO_FUND","data":{"label":"发送资金方","config":{"url":"http://fund/api","requestKey":"reqMsg","responseKey":"fundResp"}}},
      {"id":"n5","type":"END","data":{"label":"结束"}}
    ],
    "edges":[
      {"id":"e1","source":"n1","target":"n2"},
      {"id":"e2","source":"n2","target":"n3"},
      {"id":"e3","source":"n3","target":"n4"},
      {"id":"e4","source":"n4","target":"n5"}
    ]
  }
}
```

## 字段映射规则
1. 按语义推断: 姓名→userInfo.realName, 金额→loanInfo.amount, 手机→userInfo.mobile, 证件→userInfo.idType/idNo, 银行→paymentData.*
2. 枚举字段用 transform="enumMap" (证件类型/性别/利率类型/还款方式/学历/婚姻等)
3. 金额字段用 transform="formatAmount"
4. confidence: 精确=0.95, 推断=0.85, 模糊=0.70
5. fund_field 必须来自接口文档

## 流程 DSL 规则
1. 如果接口文档中包含"业务流程"章节，严格按文档描述的流程生成节点（含条件分支）
2. 文档无流程时使用默认序列: START→DATA_COLLECT(RISK)→DATA_COLLECT(CORE)→DATA_COLLECT(PAYMENT)→TEMPLATE_RENDER→SEND_TO_FUND→END
3. 条件分支: CONDITION 节点配 SpEL 表达式, 边的 conditionExpr 如 "#root.riskData.level == 'A'"
4. dataSourceCode: RISK/CORE/PAYMENT | outputKey: riskData/userInfo/paymentData

## 可用函数
- formatAmount(BigDecimal)→"100000.00"
- enumMap(enumType,internalValue)→外部枚举值
- nowDate()→"yyyy-MM-dd"

## 内部枚举参考 (上游→含义)
性别: M→男, F→女 | 证件: 01→身份证,02→护照,03→军官证 | 学历: HIGH_SCHOOL→高中,BACHELOR→本科,MASTER→硕士,PHD→博士 | 婚姻: SINGLE→未婚,MARRIED→已婚 | 风控等级: A→优质,B→良好,C→关注,D→拒绝 | 风控决策: PASS→通过,REVIEW→审查,REJECT→拒绝 | 利率: FIXED→固定,FLOATING→浮动 | 还款: EQUAL_INSTALLMENT→等额本息,EQUAL_PRINCIPAL→等额本金,BULLET→先息后本 | 卡类型: DEBIT→借记卡,CREDIT→信用卡
""";
}
