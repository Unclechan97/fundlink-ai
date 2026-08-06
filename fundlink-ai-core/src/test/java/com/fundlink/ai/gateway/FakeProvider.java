package com.fundlink.ai.gateway;

import org.springframework.stereotype.Component;

@Component("fake")
class FakeProvider implements LlmProvider {
    @Override
    public String name() { return "fake"; }
    @Override
    public boolean supports(String model) { return "fake-model".equals(model); }
    @Override
    public LlmResponse chat(LlmRequest request) {
        int inputTokens = request.getPrompt().length() / 2;
        return LlmResponse.of(
                "{\"field_mapping\":[{\"fund_field\":\"loanNo\",\"source_path\":\"loanInfo.loanNo\"}]}",
                "fake", request.getModel(),
                TokenUsage.of(inputTokens, 80), 5);
    }
}
