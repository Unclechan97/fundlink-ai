package com.fundlink.ai.gateway;

import org.springframework.stereotype.Component;

@Component("deepseek")
class FakeProvider implements LlmProvider {
    @Override
    public String name() { return "deepseek"; }
    @Override
    public boolean supports(String model) { return true; }
    @Override
    public LlmResponse chat(LlmRequest request) {
        int inputTokens = request.getPrompt().length() / 2;
        String content = """
            {
              "interface_schema": {
                "endpoint": "POST /loan/apply",
                "method": "POST",
                "fields": [
                  {"name":"loanNo","type":"String","required":true,"description":"贷款编号"},
                  {"name":"amount","type":"BigDecimal","required":true,"description":"贷款金额"},
                  {"name":"customerId","type":"String","required":true,"description":"客户ID"},
                  {"name":"customerName","type":"String","required":true,"description":"客户姓名"},
                  {"name":"idType","type":"String","required":true,"description":"证件类型"}
                ]
              },
              "field_mappings": [
                {"fund_field":"loanNo","source_path":"loanInfo.loanNo","transform":null,"confidence":0.95},
                {"fund_field":"amount","source_path":"loanInfo.amount","transform":"formatAmount","confidence":0.90},
                {"fund_field":"customerId","source_path":"userInfo.idNo","transform":null,"confidence":0.70},
                {"fund_field":"customerName","source_path":"userInfo.realName","transform":null,"confidence":0.95},
                {"fund_field":"idType","source_path":"userInfo.idType","transform":"enumMap('ID_TYPE')","confidence":0.85}
              ],
              "flow_dsl": {
                "nodes": [
                  {"id":"n1","type":"START"},
                  {"id":"n2","type":"DATA_COLLECT"},
                  {"id":"n3","type":"TEMPLATE_RENDER"},
                  {"id":"n4","type":"SEND_TO_FUND"},
                  {"id":"n5","type":"END"}
                ],
                "edges": [
                  {"id":"e1","source":"n1","target":"n2"},
                  {"id":"e2","source":"n2","target":"n3"},
                  {"id":"e3","source":"n3","target":"n4"},
                  {"id":"e4","source":"n4","target":"n5"}
                ]
              }
            }""";
        return LlmResponse.of(content, "deepseek", request.getModel(),
                TokenUsage.of(inputTokens, 200), 5);
    }
}
