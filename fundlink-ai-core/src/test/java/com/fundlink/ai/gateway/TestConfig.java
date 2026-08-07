package com.fundlink.ai.gateway;

import com.fundlink.ai.gateway.provider.QwenProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = "com.fundlink.ai")
@MapperScan("com.fundlink.ai.mapper")
public class TestConfig {

    /** 测试用 Fake Provider — 替代真实 QwenProvider */
    @Primary
    @Bean("qwen")
    public LlmProvider testQwenProvider() {
        return new LlmProvider() {
            @Override public String name() { return "qwen"; }
            @Override public boolean supports(String m) { return true; }
            @Override
            public LlmResponse chat(LlmRequest req) {
                String content = """
                    {
                      "provider_config":{"providerName":"测试银行","baseUrl":"http://test-bank/api"},
                      "interface_schema": {"endpoint":"POST /loan/apply","fields":[
                        {"name":"loanNo","type":"String","required":true,"description":"贷款编号"},
                        {"name":"amount","type":"BigDecimal","required":true,"description":"贷款金额"},
                        {"name":"customerId","type":"String","required":true,"description":"客户ID"},
                        {"name":"customerName","type":"String","required":true,"description":"客户姓名"}
                      ]},
                      "field_mappings": [
                        {"fund_field":"loanNo","source_path":"loanInfo.loanNo","transform":null,"confidence":0.95},
                        {"fund_field":"amount","source_path":"loanInfo.amount","transform":"formatAmount","confidence":0.90},
                        {"fund_field":"customerId","source_path":"userInfo.idNo","transform":null,"confidence":0.70},
                        {"fund_field":"customerName","source_path":"userInfo.realName","transform":null,"confidence":0.95}
                      ],
                      "free_marker_template": "{\\"header\\":{},\\"body\\":{\\"loanNo\\":\\"${loanNo}\\",\\"amount\\":\\"${formatAmount(amount)}\\"}}",
                      "flow_dsl": {
                        "nodes":[{"id":"n1","type":"START"},{"id":"n2","type":"DATA_COLLECT"},{"id":"n3","type":"TEMPLATE_RENDER"},{"id":"n4","type":"SEND_TO_FUND"},{"id":"n5","type":"END"}],
                        "edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"n4"},{"id":"e4","source":"n4","target":"n5"}]
                      }
                    }""";
                return LlmResponse.of(content, "qwen", "qwen-plus",
                        TokenUsage.of(100, 50), 5);
            }
        };
    }
}
