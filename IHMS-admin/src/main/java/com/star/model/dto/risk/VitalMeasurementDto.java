
package com.star.model.dto.risk;

import lombok.Data;

@Data
public class VitalMeasurementDto {
    private Double systolicPressure;
    private Double diastolicPressure;
    private Double heartRate;
    private Double spo2;
    private Double temperature;
    private Double respiratoryRate;
    private Boolean supplementalOxygen;
    private String consciousness;
    private String measuredAt;
}
