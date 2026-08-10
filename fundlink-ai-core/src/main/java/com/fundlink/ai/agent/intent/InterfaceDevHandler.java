package com.fundlink.ai.agent.intent;

import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;

/**
 * 接口开发意图处理器 — 委托给现有的 RequirementAgent 流程。
 *
 * 这是已有逻辑的入口适配，未来 Phase 3 会改为
 * DocumentSplitter → 并行子 Agent 流程。
 */
public class InterfaceDevHandler implements IntentHandler {

    private final RequirementAgent requirementAgent;

    public InterfaceDevHandler(RequirementAgent requirementAgent) {
        this.requirementAgent = requirementAgent;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.INTERFACE_DEV;
    }

    @Override
    public Object handle(IntentContext ctx) {
        String doc = ctx.getUserInput();
        String providerCode = ctx.getProviderCode() != null ? ctx.getProviderCode() : "UNKNOWN";
        String ft = FlowTypeDetector.detect(doc, null);

        RequirementResult result = requirementAgent.analyze(doc, providerCode, ft, null);
        return result;
    }
}
