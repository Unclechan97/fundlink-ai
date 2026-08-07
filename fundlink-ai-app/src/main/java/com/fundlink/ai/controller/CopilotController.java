package com.fundlink.ai.controller;

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

    private final RequirementAgent requirementAgent;

    /**
     * 需求解析：上传接口文档 → AI 生成配置
     */
    @PostMapping("/analyze")
    public ApiAiResponse<RequirementResult> analyze(@RequestBody AnalyzeRequest req) {
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode());
        return ApiAiResponse.success(result);
    }

    /**
     * 获取字段映射建议（简化返回）
     */
    @PostMapping("/suggest-mappings")
    public ApiAiResponse<List<Map<String, Object>>> suggestMappings(@RequestBody AnalyzeRequest req) {
        RequirementResult result = requirementAgent.analyze(
                req.getDocumentText(), req.getProviderCode());

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

        public int getCode() { return code; }
        public String getMsg() { return msg; }
        public T getData() { return data; }
    }
}
