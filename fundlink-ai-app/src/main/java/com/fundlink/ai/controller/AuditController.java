package com.fundlink.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fundlink.ai.entity.AiLlmAudit;
import com.fundlink.ai.mapper.AiLlmAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计日志导出 — 金融合规要求
 */
@RestController
@RequestMapping("/api/ai/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AiLlmAuditMapper auditMapper;

    /** 导出审计日志 CSV */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        List<AiLlmAudit> audits = auditMapper.selectList(
                new LambdaQueryWrapper<AiLlmAudit>()
                        .ge(AiLlmAudit::getCreateTime, fromTime)
                        .lt(AiLlmAudit::getCreateTime, toTime)
                        .orderByDesc(AiLlmAudit::getCreateTime)
        );

        String csv = "call_id,provider,model,token_input,token_output,cost_usd,latency_ms,success,trace_id,time\n"
                + audits.stream().map(a -> String.join(",",
                        esc(a.getCallId()), esc(a.getProvider()),
                        esc(a.getModel()), String.valueOf(a.getTokenInput()),
                        String.valueOf(a.getTokenOutput()), a.getCostAmount().toString(),
                        String.valueOf(a.getLatencyMs()), String.valueOf(a.getSuccess()),
                        esc(a.getTraceId()),
                        a.getCreateTime() != null ? a.getCreateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : ""))
                .collect(Collectors.joining("\n"));

        String filename = "llm-audit-" + from + "-to-" + to + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 成本汇总 */
    @GetMapping("/cost")
    public ResponseEntity<?> costSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        List<AiLlmAudit> audits = auditMapper.selectList(
                new LambdaQueryWrapper<AiLlmAudit>()
                        .ge(AiLlmAudit::getCreateTime, fromTime)
                        .lt(AiLlmAudit::getCreateTime, toTime)
                        .eq(AiLlmAudit::getSuccess, 1)
        );

        var totalCost = audits.stream()
                .map(AiLlmAudit::getCostAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        long totalCalls = audits.size();
        long totalTokens = audits.stream().mapToLong(a -> a.getTokenInput() + a.getTokenOutput()).sum();

        return ResponseEntity.ok(java.util.Map.of(
                "from", from.toString(), "to", to.toString(),
                "totalCalls", totalCalls,
                "totalTokens", totalTokens,
                "totalCostUsd", totalCost.doubleValue()
        ));
    }

    private String esc(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
