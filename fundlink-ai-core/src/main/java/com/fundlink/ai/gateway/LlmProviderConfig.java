package com.fundlink.ai.gateway;

import com.fundlink.ai.gateway.provider.OpenAiCompatibleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM Provider 注册 — 为 yml 中配置的每个 provider 创建对应的 bean。
 * <p>
 * Bean 名称与 provider name 一致，供 {@link LlmGatewayImpl} 的 Map 注入使用。
 * 当 fundlink.llm.providers.siliconflow.base-url 未设置时不加载（测试环境用 TestConfig 的 fake bean）。
 */
@Configuration
@ConditionalOnProperty("fundlink.llm.providers.siliconflow.base-url")
public class LlmProviderConfig {

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public LlmProviderConfig(
            @Value("${fundlink.llm.connect-timeout-seconds:30}") int connectTimeoutSeconds,
            @Value("${fundlink.llm.request-timeout-minutes:20}") int requestTimeoutMinutes) {
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        this.requestTimeout = Duration.ofMinutes(requestTimeoutMinutes);
    }

    @Bean
    public OpenAiCompatibleProvider siliconflow(
            @Value("${fundlink.llm.providers.siliconflow.base-url}") String baseUrl,
            @Value("${fundlink.llm.providers.siliconflow.api-key}") String apiKey,
            @Value("${fundlink.llm.providers.siliconflow.default-model:Qwen/Qwen3-8B}") String defaultModel) {
        return new OpenAiCompatibleProvider("siliconflow", baseUrl, apiKey,
                defaultModel, connectTimeout, requestTimeout);
    }

    @Bean
    public OpenAiCompatibleProvider qwen(
            @Value("${fundlink.llm.providers.qwen.base-url}") String baseUrl,
            @Value("${fundlink.llm.providers.qwen.api-key}") String apiKey,
            @Value("${fundlink.llm.providers.qwen.default-model:qwen-plus}") String defaultModel) {
        return new OpenAiCompatibleProvider("qwen", baseUrl, apiKey,
                defaultModel, connectTimeout, requestTimeout);
    }

    @Bean
    public OpenAiCompatibleProvider deepseek(
            @Value("${fundlink.llm.providers.deepseek.base-url}") String baseUrl,
            @Value("${fundlink.llm.providers.deepseek.api-key}") String apiKey,
            @Value("${fundlink.llm.providers.deepseek.default-model:deepseek-chat}") String defaultModel) {
        return new OpenAiCompatibleProvider("deepseek", baseUrl, apiKey,
                defaultModel, connectTimeout, requestTimeout);
    }
}
