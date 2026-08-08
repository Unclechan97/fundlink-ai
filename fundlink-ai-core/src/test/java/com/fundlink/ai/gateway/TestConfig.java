package com.fundlink.ai.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = "com.fundlink.ai")
@MapperScan("com.fundlink.ai.mapper")
public class TestConfig {

    /** Test: @Async runs synchronously so assertions don't race */
    @Bean
    public TaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }

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

    /** 测试用 DeepSeek Fake Provider — 与 qwen 返回相同预制 JSON */
    @Bean("deepseek")
    public LlmProvider testDeepSeekProvider() {
        return new LlmProvider() {
            @Override public String name() { return "deepseek"; }
            @Override public boolean supports(String m) { return true; }
            @Override
            public LlmResponse chat(LlmRequest req) {
                String content = """
                    {
                      "previewData":{"userInfo":{"realName":"测试"},"loanInfo":{"loanNo":"LN001","amount":50000},"riskData":{"score":85,"level":"A"}},
                      "testCases":[
                        {"name":"A级-正常","targetBranch":"ec1","scenarioType":"NORMAL","inputData":{"loanNo":"LN001","amount":50000},"expectedContextKeys":{"riskData":"exists"},"mockRules":[{"sourceCode":"RISK","responseJson":"{\\"score\\":85,\\"level\\":\\"A\\"}"}]},
                        {"name":"非A级-拒绝","targetBranch":"ec2","scenarioType":"NORMAL","inputData":{"loanNo":"LN002","amount":30000},"expectedContextKeys":{"riskData":"exists"},"mockRules":[{"sourceCode":"RISK","responseJson":"{\\"score\\":55,\\"level\\":\\"B\\"}"}]}
                      ],
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
                      "flow_dsl": {
                        "nodes":[{"id":"n1","type":"START"},{"id":"n2","type":"DATA_COLLECT"},{"id":"n3","type":"TEMPLATE_RENDER"},{"id":"nc","type":"CONDITION"},{"id":"n4","type":"SEND_TO_FUND"},{"id":"n5","type":"END"}],
                        "edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"nc"},{"id":"ec1","source":"nc","target":"n4","conditionExpr":"#root.riskData.level == 'A'"},{"id":"ec2","source":"nc","target":"n5","conditionExpr":"#root.riskData.level != 'A'"}]
                      }
                    }""";
                return LlmResponse.of(content, "deepseek", "deepseek-chat",
                        TokenUsage.of(100, 50), 5);
            }
        };
    }
}
