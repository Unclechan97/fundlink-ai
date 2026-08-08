package com.fundlink.ai.controller;

import com.fundlink.ai.agent.ConfigWriter;
import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Copilot REST API — 前端对话交互入口
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class CopilotController {

    private final ConfigWriter configWriter;
    private final RequirementAgent requirementAgent;

    /**
     * 需求解析：上传接口文档 → AI 生成配置
     */
    @PostMapping("/analyze")
    public ApiAiResponse<RequirementResult> analyze(@RequestBody AnalyzeRequest req) {
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode(), null);
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
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode(), null);

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

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
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
}
