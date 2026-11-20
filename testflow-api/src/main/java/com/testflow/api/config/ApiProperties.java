package com.testflow.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("testflow")
public record ApiProperties(
    String secretKey,
    String bootstrapAdminUsername,
    String bootstrapAdminPassword,
    String loginRatePrefix) {}
