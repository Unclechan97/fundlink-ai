package com.fundlink.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FundLink AI — AI驱动资金接入全生命周期管理平台
 */
@EnableScheduling
@EnableAsync
@SpringBootApplication(scanBasePackages = "com.fundlink.ai")
@MapperScan("com.fundlink.ai.mapper")
public class FundLinkAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FundLinkAiApplication.class, args);
    }
}
