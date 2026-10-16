package com.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "disease-risk")
public class DiseaseRiskProperties {
    private boolean enabled = true;
    private String serviceBaseUrl = "http://127.0.0.1:8001";
    private String expectedModelVersion = "nhefs5-opt-20261015-v2";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 15000;
}
