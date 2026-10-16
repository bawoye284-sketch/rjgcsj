
package com.star.model.dto.risk;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonalizedRiskResponse {
    @JsonAlias("user_id")
    private Integer userId;
    @JsonAlias("group_risk_probability")
    private Double groupRiskProbability;
    @JsonAlias("personal_change_score")
    private Double personalChangeScore;
    @JsonAlias("baseline_available")
    private Boolean baselineAvailable;
    @JsonAlias("risk_level")
    private String riskLevel;
    @JsonAlias("news2_complete")
    private Boolean news2Complete;
    @JsonAlias("single_alerts")
    private List<String> singleAlerts;
    @JsonAlias("assessment_basis")
    private String assessmentBasis;
    private List<String> reasons;
    @JsonAlias("baseline_details")
    private List<Map<String, Object>> baselineDetails;
    @JsonAlias("model_name")
    private String modelName;
    @JsonAlias("model_version")
    private String modelVersion;
    private String note;
    private String assessmentTime;
    private Long assessmentId;
    private String requestId;
}
