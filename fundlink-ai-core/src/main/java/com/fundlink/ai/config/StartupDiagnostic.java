package com.fundlink.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/**
 * Startup diagnostic — prints system status at boot so you can see what's working in IDEA console.
 */
@Slf4j
@Component
public class StartupDiagnostic {

    @Value("${fundlink.admin.base-url:http://localhost:8080}")
    private String fundlinkUrl;

    @Value("${fundlink.rag.base-url:http://localhost:8000}")
    private String ragUrl;

    @Value("${fundlink.llm.providers.qwen.api-key:}")
    private String qwenKey;

    @Value("${fundlink.llm.providers.deepseek.api-key:}")
    private String deepseekKey;

    @Value("${fundlink.llm.router.default-provider:deepseek}")
    private String defaultProvider;

    @Value("${fundlink.llm.router.fallback-chain:deepseek,qwen,siliconflow}")
    private String fallbackChain;

    @Value("${spring.datasource.url:}")
    private String dbUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void diagnose() {
        log.info("");
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║     FundLink AI Agent Loop — Startup         ║");
        log.info("╠══════════════════════════════════════════════╣");

        // DB
        log.info("║ DB:    {}", maskUrl(dbUrl));
        // LLM keys
        log.info("║ Qwen key:    {}", keyStatus(qwenKey));
        log.info("║ DeepSeek key:{}", keyStatus(deepseekKey));
        log.info("║ Router:      {}  fallback: {}", defaultProvider, fallbackChain);
        // Upstream services
        log.info("║ FundLink:    {} → {}", fundlinkUrl, checkHttp(fundlinkUrl + "/api/admin/providers?page=1&size=1"));
        log.info("║ RAG:         {} → {}", ragUrl, checkHttp(ragUrl + "/token"));
        log.info("╚══════════════════════════════════════════════╝");
        log.info("");

        if (isBlank(qwenKey) && isBlank(deepseekKey)) {
            log.error("!!! 没有配置任何 LLM API Key！所有 AI 功能将不可用！");
            log.error("!!! 请在环境变量中设置 QWEN_API_KEY 或 DEEPSEEK_API_KEY");
        }
    }

    private String keyStatus(String key) {
        if (isBlank(key)) return "NOT SET ⚠️";
        return "***" + key.substring(Math.max(0, key.length() - 4));
    }

    private String checkHttp(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URI(url).toURL().openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(3000);
            int code = c.getResponseCode();
            return code == 200 ? "✅" : "HTTP " + code + " ⚠️";
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    private String maskUrl(String url) {
        if (url == null) return "NOT SET ⚠️";
        return url.replaceAll("://.*@", "://***@").replaceAll("\\?.*", "");
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
