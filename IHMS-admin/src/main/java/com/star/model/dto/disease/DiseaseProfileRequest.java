package com.star.model.dto.disease;

import lombok.Data;
import java.util.Map;

@Data
public class DiseaseProfileRequest {
    private Long revision;
    private Map<String, Object> profile;
    private Map<String, Object> diseaseHistory;
    private Map<String, Object> diagnosisYears;
}
