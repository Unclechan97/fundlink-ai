package com.fundlink.ai.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 测试配置 — 最小化 Spring 启动
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = "com.fundlink.ai")
@MapperScan("com.fundlink.ai.mapper")
public class TestConfig {
}
