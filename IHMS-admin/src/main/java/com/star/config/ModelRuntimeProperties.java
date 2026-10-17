package com.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "model-runtime")
public class ModelRuntimeProperties {
    private boolean autoStart = Boolean.parseBoolean(System.getenv().getOrDefault("IHMS_MODEL_AUTO_START", "true"));
    private String projectRoot = System.getenv().getOrDefault("IHMS_PROJECT_ROOT", "");
    private String pythonExecutable = System.getenv().getOrDefault("IHMS_MODEL_PYTHON", "");
    private int startupTimeoutSeconds = 90;
    private int shutdownTimeoutSeconds = 10;
    private int checkIntervalSeconds = 5;
}
