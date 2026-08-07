package com.fundlink.ai.agent.diagnosis;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisAgentImpl implements DiagnosisAgent {

    private final LlmGateway llmGateway;

    @Override
    public DiagnosisResult diagnose(String instanceNo, String errorDescription) {
        // 先用规则引擎做快速诊断，不确定时调 LLM
        DiagnosisResult result = ruleBasedDiagnose(instanceNo, errorDescription);
        if (result.getConfidence() < 0.7) {
            result = llmBasedDiagnose(instanceNo, errorDescription);
        }
        return result;
    }

    private DiagnosisResult ruleBasedDiagnose(String instanceNo, String error) {
        DiagnosisResult r = new DiagnosisResult();
        List<String> chain = new ArrayList<>();

        // 解析链式错误: "A → B → C"
        if (error.contains("→")) {
            for (String step : error.split("→")) {
                chain.add(step.trim());
            }
        }
        r.setCauseChain(chain);

        // 如果错误字符串中有箭头分隔，已经解析出了链式原因，直接匹配规则
        if (!chain.isEmpty() && chain.size() > 1) {
            r.setRootCause("流程执行链式异常: " + chain.get(0));
            r.setFixSuggestion("逐节点排查: " + String.join(" → ", chain));
            r.setConfidence(0.7);
            return r;
        }

        // 规则1: 模板渲染错误
        if (error.contains("template") || error.contains("模板") || error.contains("FreeMarker")) {
            chain.add("模板渲染引擎报错");
            if (error.contains("null") || error.contains("缺失") || error.contains("mapping")) {
                chain.add("字段映射配置缺失或值为null");
                r.setRootCause("字段映射配置错误：模板变量未找到对应的数据源字段");
                r.setFixSuggestion("检查 fl_field_mapping 表，确认模板引用的每个变量都有对应的 source_path 配置");
                r.setConfidence(0.85);
                return r;
            }
            r.setRootCause("模板语法错误或渲染参数不完整");
            r.setFixSuggestion("使用模板预览接口(/api/admin/templates/{id}/preview)检查模板");
            r.setConfidence(0.75);
            return r;
        }

        // 规则2: 数据源调用失败
        if (error.contains("数据源") || error.contains("DATA_COLLECT") || error.contains("超时")) {
            chain.add("数据收集节点执行异常");
            chain.add("数据源接口调用失败或超时");
            r.setRootCause("数据源调用异常：目标服务不可用或响应超时");
            r.setFixSuggestion("1. 检查 Mock 平台是否启用(use_mock=1) 2. 检查数据源真实URL连通性 3. 增加超时配置(timeout_ms)");
            r.setConfidence(0.80);
            return r;
        }

        // 规则3: 流程配置错误
        if (error.contains("SpEL") || error.contains("condition") || error.contains("CONDITION")) {
            chain.add("条件分支表达式执行异常");
            r.setRootCause("条件表达式(SpEL)配置错误：语法不正确或引用的变量不存在");
            r.setFixSuggestion("检查流程编辑器中 CONDITION 节点的 expression 配置");
            r.setConfidence(0.82);
            return r;
        }

        // 兜底: 用 LLM
        r.setConfidence(0.3);
        return r;
    }

    private DiagnosisResult llmBasedDiagnose(String instanceNo, String error) {
        DiagnosisResult r = new DiagnosisResult();
        try {
            String prompt = "你是资金接入系统故障诊断专家。分析以下流程错误: instanceNo=" +
                    instanceNo + ", error=" + error +
                    "。输出JSON: {\"root_cause\":\"\",\"cause_chain\":[],\"fix_suggestion\":\"\"}";
            String traceId = "diag-" + UUID.randomUUID().toString().substring(0, 8);
            LlmRequest request = LlmRequest.of("deepseek", "deepseek-chat", prompt, traceId);
            String content = llmGateway.chat(request).getContent();
            // 简化: 解析 JSON 或使用 FakeProvider 默认值
            List<String> chain = new ArrayList<>();
            chain.add("错误信息: " + error);
            r.setCauseChain(chain);
            r.setRootCause("需要进一步排查: " + error.substring(0, Math.min(60, error.length())));
            r.setFixSuggestion("建议查看流程实例详情和 API 调用日志");
            r.setConfidence(0.6);
        } catch (Exception e) {
            log.error("LLM diagnosis failed", e);
            r.setRootCause("诊断服务异常");
            r.setFixSuggestion("请人工排查");
            r.setConfidence(0.1);
        }
        return r;
    }
}
