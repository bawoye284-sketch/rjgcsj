package com.star.model.dto.risk;

import lombok.Data;

/** The shared form's explicit inputs; never substitutes another tab's latest measurement. */
@Data
public class CurrentRiskAssessmentDto {
    private ElderRiskProfileDto profile;
    private VitalMeasurementDto measurement;
    private String requestId;
}
