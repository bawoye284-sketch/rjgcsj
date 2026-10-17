package com.star.service.disease;

import com.star.config.DiseaseRiskProperties;
import com.star.service.risk.ModelRuntime;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class DiseaseRiskModelClient {
    private final DiseaseRiskProperties properties;
    private final RestTemplate http;
    private final ModelRuntime runtime;
    public DiseaseRiskModelClient(DiseaseRiskProperties properties, ModelRuntime runtime) {
        this.properties = properties;
        this.runtime = runtime;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs()); factory.setReadTimeout(properties.getReadTimeoutMs());
        this.http = new RestTemplate(factory);
    }
    public Map<String, Object> exchange(String path, Map<String, Object> body) {
        if (!properties.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "慢性病预测功能已关闭");
        runtime.requireReady();
        try {
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> result = http.exchange(properties.getServiceBaseUrl().replaceAll("/+$", "") + path,
                    body == null ? HttpMethod.GET : HttpMethod.POST, new HttpEntity<>(body, headers), Map.class).getBody();
            if (result == null || !properties.getExpectedModelVersion().equals(result.get("model_version")))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型版本不一致，请启动指定的慢性病模型服务");
            if (!Boolean.TRUE.equals(result.get("research_only")))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型响应缺少研究版本标识");
            return result;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(error.getRawStatusCode() == 422 ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE,
                    error.getRawStatusCode() == 422 ? "模型输入校验失败，请核对资料" : "慢性病模型服务返回错误，请稍后重试");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "后端模型连接中断或响应超时，请稍后重试并查看后端日志");
        }
    }
    public Map<String, Object> models() {
        if (!properties.isEnabled()) {
            Map<String, Object> disabled = new LinkedHashMap<>(); disabled.put("enabled", false);
            disabled.put("diseases", Collections.emptyList()); disabled.put("model_version", properties.getExpectedModelVersion());
            return disabled;
        }
        Map<String, Object> result = exchange("/models", null); result.put("enabled", true);
        result.put("runtime", runtime.status()); return result;
    }
    public Map<String, Object> predict(Map<String, Object> input) {
        Map<String, Object> response = exchange("/predict/disease", input);
        if (!(response.get("validation_status") instanceof String) || !(response.get("results") instanceof List))
            throw invalid();
        Set<String> seen = new HashSet<>();
        for (Object raw : (List<?>) response.get("results")) {
            if (!(raw instanceof Map)) throw invalid();
            Map<?, ?> item = (Map<?, ?>) raw;
            String code = String.valueOf(item.get("disease_code")), status = String.valueOf(item.get("status"));
            if (!DiseaseRiskContract.CODES.contains(code) || !seen.add(code)
                    || !(item.get("missing_fields") instanceof List) || !(item.get("disease_name") instanceof String)) throw invalid();
            if ("predicted".equals(status)) {
                if (!probability(item.get("probability")) || !probability(item.get("threshold"))
                        || !(item.get("alert") instanceof Boolean) || !(item.get("threshold_policy") instanceof String)) throw invalid();
                boolean alert = ((Number) item.get("probability")).doubleValue() >= ((Number) item.get("threshold")).doubleValue();
                if (!Objects.equals(alert, item.get("alert"))) throw invalid();
            } else if (!Arrays.asList("already_diagnosed", "insufficient_data", "not_applicable", "model_unavailable").contains(status)
                    || item.get("probability") != null || item.get("alert") != null) throw invalid();
        }
        if (!seen.equals(new HashSet<>(DiseaseRiskContract.CODES))) throw invalid();
        return response;
    }
    private boolean probability(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue() >= 0 && ((Number) value).doubleValue() <= 1;
    }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型返回的五病种结果不完整或格式无效，本次未保存");
    }
}
