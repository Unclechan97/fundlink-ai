package com.fundlink.ai.controller;

import com.fundlink.ai.agent.ConfigWriter;
import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.intent.*;
import com.fundlink.ai.agent.loop.LoopEventPublisher;
import com.fundlink.ai.agent.loop.MultiInterfaceOrchestrator;
import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import com.fundlink.ai.agent.requirement.MultiInterfaceResult;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.agent.split.DocumentSplitter;
import com.fundlink.ai.agent.split.InterfaceDeduplicator;
import com.fundlink.ai.agent.split.InterfaceSegment;
import com.fundlink.ai.gateway.LlmGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Copilot REST API — 前端对话交互入口
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class CopilotController {

    private final ConfigWriter configWriter;
    private final RequirementAgent requirementAgent;
    private final LlmGateway llmGateway;
    private final LoopEventPublisher eventPublisher;
    private final PromptBuilder promptBuilder;

    private DocumentSplitter documentSplitter;
    private IntentRouter intentRouter;
    private MultiInterfaceOrchestrator multiInterfaceOrchestrator;
    private Map<IntentType, IntentHandler> handlers;

    @PostConstruct
    void init() {
        documentSplitter = new DocumentSplitter(new InterfaceDeduplicator());
        intentRouter = new IntentRouter(llmGateway);
        multiInterfaceOrchestrator = new MultiInterfaceOrchestrator(
                requirementAgent, configWriter, promptBuilder, eventPublisher);
        handlers = new LinkedHashMap<>();
        handlers.put(IntentType.INTERFACE_DEV, new InterfaceDevHandler(requirementAgent));
        handlers.put(IntentType.KNOWLEDGE_QA, new KnowledgeQaHandler(llmGateway));
        handlers.put(IntentType.TROUBLESHOOTING, new TroubleshootingHandler(llmGateway));
    }

    /**
     * 需求解析：上传接口文档 → AI 生成配置。
     *
     * Phase 3: 传 selectedInterfaceIds 时走多接口并行处理。
     * 不传时退化为单接口处理（向后兼容）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/analyze")
    public ApiAiResponse<Object> analyze(@RequestBody AnalyzeRequest req) {
        String ft = FlowTypeDetector.detect(req.getDocumentText(), req.getFlowType());

        // ── 多接口模式 ──
        if (req.getSelectedInterfaceIds() != null && !req.getSelectedInterfaceIds().isEmpty()) {
            List<InterfaceSegment> allSegments = documentSplitter.split(req.getDocumentText());
            List<InterfaceSegment> selected = allSegments.stream()
                    .filter(s -> req.getSelectedInterfaceIds().contains(s.getInterfaceId()))
                    .toList();

            if (selected.isEmpty()) {
                return ApiAiResponse.error("未找到选中的接口", null);
            }

            MultiInterfaceResult multiResult = multiInterfaceOrchestrator.processInterfaces(
                    selected, req.getProviderCode(), ft);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("providerCode", multiResult.getProviderCode());
            data.put("totalCount", multiResult.getTotalCount());
            data.put("successCount", multiResult.getSuccessCount());
            data.put("failedCount", multiResult.getFailedCount());
            List<Map<String, Object>> items = new ArrayList<>();
            for (MultiInterfaceResult.InterfaceResultItem item : multiResult.getInterfaces()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("interfaceId", item.getInterfaceId());
                m.put("interfaceName", item.getInterfaceName());
                m.put("status", item.getStatus());
                m.put("errorMessage", item.getErrorMessage());
                m.put("result", item.getResult());
                items.add(m);
            }
            data.put("interfaces", items);

            // 单接口时返回扁平结果（兼容旧 UI）
            if (selected.size() == 1 && multiResult.getSuccessCount() == 1) {
                RequirementResult single = multiResult.getInterfaces().get(0).getResult();
                if (single != null && single.getParseError() == null) {
                    return ApiAiResponse.success(single);
                }
            }
            return ApiAiResponse.success(data);
        }

        // ── 单接口模式（向后兼容）──
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode(), ft, null);
        if (result.getParseError() != null) {
            return ApiAiResponse.error("AI 解析异常: " + result.getParseError(), result);
        }
        return ApiAiResponse.success(result);
    }

    /**
     * 审核通过后一键写入 FundLink
     */
    @PostMapping("/apply")
    public ApiAiResponse<ConfigWriter.WriteResult> apply(@RequestBody ApplyRequest req) {
        RequirementResult result = req.getResult();
        ConfigWriter.WriteResult write = configWriter.writeAll(
                result, req.getProviderCode(), req.getFlowType());
        return ApiAiResponse.success(write);
    }

    /**
     * 获取字段映射建议（简化返回）
     */
    @PostMapping("/suggest-mappings")
    public ApiAiResponse<List<Map<String, Object>>> suggestMappings(@RequestBody AnalyzeRequest req) {
        String ft = FlowTypeDetector.detect(req.getDocumentText(), req.getFlowType());
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode(), ft, null);

        List<Map<String, Object>> mappings = result.getFieldMappings().stream()
                .<Map<String, Object>>map(m -> {
                    java.util.HashMap<String, Object> map = new java.util.HashMap<>();
                    map.put("fundField", m.getFundField());
                    map.put("sourcePath", m.getSourcePath());
                    map.put("transform", m.getTransform() != null ? m.getTransform() : "");
                    map.put("confidence", m.getConfidence());
                    return map;
                }).toList();

        return ApiAiResponse.success(mappings);
    }

    // -- 请求/响应 DTO --

    public static class AnalyzeRequest {
        private String documentText;
        private String providerCode;
        private String flowType;
        private List<String> selectedInterfaceIds;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
        public List<String> getSelectedInterfaceIds() { return selectedInterfaceIds; }
        public void setSelectedInterfaceIds(List<String> ids) { this.selectedInterfaceIds = ids; }
    }

    public static class ApplyRequest {
        private RequirementResult result;
        private String providerCode;
        private String flowType = "LOAN";

        public RequirementResult getResult() { return result; }
        public void setResult(RequirementResult r) { this.result = r; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
    }

    public static class ApiAiResponse<T> {
        private int code;
        private String msg;
        private T data;

        public static <T> ApiAiResponse<T> success(T data) {
            ApiAiResponse<T> r = new ApiAiResponse<>();
            r.code = 0;
            r.msg = "ok";
            r.data = data;
            return r;
        }

        public static <T> ApiAiResponse<T> error(String msg, T data) {
            ApiAiResponse<T> r = new ApiAiResponse<>();
            r.code = -1;
            r.msg = msg;
            r.data = data;
            return r;
        }

        public int getCode() { return code; }
        public String getMsg() { return msg; }
        public T getData() { return data; }
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2: 新端点
    // ═══════════════════════════════════════════════════════════

    /**
     * 意图识别：用户输入 → 意图类型 + 置信度。
     */
    @PostMapping("/intent")
    public ApiAiResponse<Map<String, Object>> detectIntent(@RequestBody Map<String, String> req) {
        String input = req.getOrDefault("userInput", "");
        IntentResult result = intentRouter.route(input);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", result.getIntentType().name());
        data.put("intentDisplay", result.getIntentType().getDisplayName());
        data.put("confidence", result.getConfidence());
        data.put("reason", result.getReason());
        data.put("needUserConfirm", result.isNeedUserConfirm());
        data.put("extractedInfo", result.getExtractedInfo());

        return ApiAiResponse.success(data);
    }

    /**
     * 文档拆分：多接口文档 → 接口片段列表 + 去重报告。
     */
    @PostMapping("/split")
    public ApiAiResponse<Map<String, Object>> splitDocument(@RequestBody Map<String, String> req) {
        String doc = req.getOrDefault("documentText", "");
        List<InterfaceSegment> segments = documentSplitter.split(doc);

        List<Map<String, Object>> interfaceList = segments.stream()
                .map(seg -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("interfaceId", seg.getInterfaceId());
                    m.put("interfaceName", seg.getInterfaceName());
                    m.put("endpoint", seg.getEndpoint());
                    m.put("method", seg.getMethod());
                    m.put("flowType", seg.getFlowType());
                    m.put("splitConfidence", seg.getSplitConfidence());
                    m.put("splitSource", seg.getSplitSource().name());
                    m.put("index", seg.getIndex());
                    // 前 200 字符预览
                    String text = seg.getSectionText();
                    m.put("sectionPreview", text != null && text.length() > 200
                            ? text.substring(0, 200) + "..." : text);
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCount", segments.size());
        data.put("interfaces", interfaceList);
        data.put("deduplications", List.of());   // Phase 3 补充
        data.put("warnings", List.of());

        return ApiAiResponse.success(data);
    }

    /**
     * 知识问答：业务问题 → LLM 直接回答。
     */
    @PostMapping("/qa")
    public ApiAiResponse<Map<String, Object>> qa(@RequestBody Map<String, String> req) {
        String input = req.getOrDefault("userInput", "");
        IntentContext ctx = IntentContext.of(input, null);

        IntentHandler handler = handlers.get(IntentType.KNOWLEDGE_QA);
        if (handler == null) {
            return ApiAiResponse.error("QA handler not available", null);
        }

        KnowledgeQaHandler.QaResult result = (KnowledgeQaHandler.QaResult) handler.handle(ctx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", "KNOWLEDGE_QA");
        data.put("answer", result.getAnswer());

        return ApiAiResponse.success(data);
    }

    /**
     * 问题排查：报错日志 → LLM 分析诊断。
     */
    @PostMapping("/troubleshoot")
    public ApiAiResponse<Map<String, Object>> troubleshoot(@RequestBody Map<String, String> req) {
        String input = req.getOrDefault("userInput", "");
        IntentContext ctx = IntentContext.of(input, null);

        IntentHandler handler = handlers.get(IntentType.TROUBLESHOOTING);
        if (handler == null) {
            return ApiAiResponse.error("Troubleshoot handler not available", null);
        }

        TroubleshootingHandler.TroubleshootResult result =
                (TroubleshootingHandler.TroubleshootResult) handler.handle(ctx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", "TROUBLESHOOTING");
        data.put("analysis", result.getAnalysis());

        return ApiAiResponse.success(data);
    }
}
