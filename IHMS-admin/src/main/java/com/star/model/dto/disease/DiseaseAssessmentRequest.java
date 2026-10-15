package com.star.model.dto.disease;

import lombok.Data;

@Data
public class DiseaseAssessmentRequest {
    private Long profileRevision;
    private String requestId;
}
