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
你是资金接入系统配置专家。严格按JSON Schema输出配置，不要输出额外内容。

## JSON Schema
```json
{
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
    {"fund_field":"custName","source_path":"userInfo.realName","transform":"formatAmount","confidence":0.95}
  ],
  "free_marker_template": "{ \\"header\\":{...}, \\"body\\":{...} }",
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
### provider_config
- providerName: 从文档标题或概述中提取资金方名称
- baseUrl: 从文档中的请求地址提取基础URL(如只到/api，不含具体路径)

### field_mappings
- 按语义推断: 姓名→userInfo.realName, 金额→loanInfo.amount, 手机→userInfo.mobile, 证件号→userInfo.idNo/idType, 银行卡→paymentData.*
- 只有两个transform: formatAmount(金额字段,如applyAmount/amount), nowDate(日期字段)
- 大部分字段transform为null,表示直接透传
- confidence: 精确匹配=0.95, 语义推断=0.85, 模糊=0.70
- fund_field必须来自接口文档字段名

### free_marker_template
- 生成完整的FreeMarker模板,包含请求头(如果有)和请求体
- 变量名与field_mappings中的fund_field一一对应, 如 ${custName} ${applyAmount}
- 金额字段用${formatAmount(applyAmount)}, 日期用${nowDate()}, 其他直接${字段名}

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
