
package com.star.service.risk;

import com.star.model.dto.risk.PersonalizedRiskResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Slf4j
public class RiskModelClient {

    @Value("${risk-model.service-url:http://127.0.0.1:8001/predict}")
    private String serviceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public PersonalizedRiskResponse predict(Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        ResponseEntity<PersonalizedRiskResponse> response = restTemplate.exchange(
                serviceUrl, HttpMethod.POST, entity, PersonalizedRiskResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("个性化风险模型服务返回异常");
        }
        return response.getBody();
    }
}
