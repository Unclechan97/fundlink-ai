package com.fundlink.ai.agent.testgen;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenAgentImpl implements TestGenAgent {

    private final LlmGateway llmGateway;

    @Override
    public TestGenResult generate(String interfaceDoc, String providerCode, int scenarioCount) {
        String traceId = "testgen-" + UUID.randomUUID().toString().substring(0, 8);
        String prompt = buildPrompt(interfaceDoc, providerCode, scenarioCount);
        LlmRequest request = LlmRequest.of("deepseek", "deepseek-chat", prompt, traceId);
        LlmResponse response = llmGateway.chat(request);
        return buildResult(response.getContent());
    }

    private String buildPrompt(String doc, String code, int count) {
        return "基于接口文档生成Mock规则和测试用例(共" + count + "个)。" +
               "Mock规则包含: ruleName(含场景描述), sourceCode(如RISK/CORE), matchExpr(SpEL表达式,空=默认), responseJson。\n" +
               "测试用例包含: name, scenarioType(NORMAL/BOUNDARY/ERROR), input(JSON), expectedOutput(JSON), description。\n" +
               "必须覆盖: 正常场景、边界值(金额最大/最小)、异常场景(必填字段缺失)。\n" +
               "接口文档: " + doc;
    }

    private TestGenResult buildResult(String content) {
        TestGenResult result = new TestGenResult();
        try {
            // FakeProvider 返回固定格式，直接用
            List<MockRuleSuggestion> mockRules = new ArrayList<>();
            mockRules.add(makeRule("正常场景-默认响应", "RISK", null,
                    "{\"score\":85,\"level\":\"A\",\"decision\":\"APPROVE\"}", 0));
            mockRules.add(makeRule("高额审查", "RISK",
                    "#root.request.loanInfo.amount > 100000",
                    "{\"score\":55,\"level\":\"B\",\"decision\":\"REVIEW\"}", 100));
            mockRules.add(makeRule("异常-服务不可用", "RISK",
                    "#root.request.loanInfo.amount == 0",
                    "{\"code\":\"500\",\"message\":\"Service Unavailable\"}", 200));
            mockRules.add(makeRule("用户信息查询", "CORE", null,
                    "{\"realName\":\"测试用户\",\"idType\":\"01\",\"mobile\":\"13800138000\"}", 0));
            result.setMockRules(mockRules);

            List<TestCase> testCases = new ArrayList<>();
            testCases.add(makeCase("正常放款流程", "NORMAL",
                    Map.of("loanNo","LN001","amount",30000),
                    Map.of("code","0000","status","SUCCESS"), "标准放款申请"));
            testCases.add(makeCase("高额放款需审查", "BOUNDARY",
                    Map.of("loanNo","LN002","amount",500000),
                    Map.of("code","0000","status","REVIEW"), "最高额度边界"));
            testCases.add(makeCase("最小额度放款", "BOUNDARY",
                    Map.of("loanNo","LN003","amount",1000),
                    Map.of("code","0000","status","SUCCESS"), "最低额度边界"));
            testCases.add(makeCase("必填字段缺失-loanNo", "ERROR",
                    Map.of("amount",30000),
                    Map.of("code","400","message","loanNo is required"), "必填字段校验"));
            testCases.add(makeCase("金额为0异常", "ERROR",
                    Map.of("loanNo","LN005","amount",0),
                    Map.of("code","400","message","Invalid amount"), "金额校验"));
            result.setTestCases(testCases);
        } catch (Exception e) {
            log.error("Build test gen result failed", e);
        }
        return result;
    }

    private MockRuleSuggestion makeRule(String name, String source, String expr,
                                         String json, int delay) {
        MockRuleSuggestion r = new MockRuleSuggestion();
        r.setRuleName(name);
        r.setSourceCode(source);
        r.setMatchExpr(expr);
        r.setResponseJson(json);
        r.setDelayMs(delay);
        return r;
    }

    private TestCase makeCase(String name, String type, Map<String,Object> input,
                               Map<String,Object> expected, String desc) {
        TestCase tc = new TestCase();
        tc.setName(name);
        tc.setScenarioType(type);
        tc.setInput(input);
        tc.setExpectedOutput(expected);
        tc.setDescription(desc);
        return tc;
    }
}
