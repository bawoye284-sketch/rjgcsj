
package com.star.service.risk;

import com.alibaba.fastjson2.JSON;
import com.star.model.dto.risk.ElderRiskProfileDto;
import com.star.model.dto.risk.PersonalizedRiskResponse;
import com.star.model.dto.risk.VitalMeasurementDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PersonalizedRiskService {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private RiskModelClient riskModelClient;

    public Map<String, Object> getProfile(Integer userId) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT age,sex_male,bmi,heart_disease,smoking_current,drinking_any," +
                "drinks_per_week,physical_activity FROM elder_risk_profile WHERE user_id=?", userId);
        if (list.isEmpty()) {
            return null;
        }
        Map<String, Object> row = list.get(0);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("age", row.get("age"));
        profile.put("sexMale", row.get("sex_male"));
        profile.put("bmi", row.get("bmi"));
        profile.put("heartDisease", row.get("heart_disease"));
        profile.put("smokingCurrent", row.get("smoking_current"));
        profile.put("drinkingAny", row.get("drinking_any"));
        profile.put("drinksPerWeek", row.get("drinks_per_week"));
        profile.put("physicalActivity", row.get("physical_activity"));
        return profile;
    }

    public void saveProfile(Integer userId, ElderRiskProfileDto dto) {
        if (getProfile(userId) == null) {
            jdbcTemplate.update(
                    "INSERT INTO elder_risk_profile " +
                    "(user_id,age,sex_male,bmi,heart_disease,smoking_current,drinking_any,drinks_per_week,physical_activity,updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    userId, dto.getAge(), dto.getSexMale(), dto.getBmi(), dto.getHeartDisease(),
                    dto.getSmokingCurrent(), dto.getDrinkingAny(), dto.getDrinksPerWeek(), dto.getPhysicalActivity());
        } else {
            jdbcTemplate.update(
                    "UPDATE elder_risk_profile SET age=?,sex_male=?,bmi=?,heart_disease=?," +
                    "smoking_current=?,drinking_any=?,drinks_per_week=?,physical_activity=?," +
                    "updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                    dto.getAge(), dto.getSexMale(), dto.getBmi(), dto.getHeartDisease(),
                    dto.getSmokingCurrent(), dto.getDrinkingAny(), dto.getDrinksPerWeek(),
                    dto.getPhysicalActivity(), userId);
        }
    }

    public void saveMeasurement(Integer userId, VitalMeasurementDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请填写本次测量");
        }
        if (dto.getConsciousness() != null && !Arrays.asList(
                "alert", "new_confusion", "voice", "pain", "unresponsive").contains(dto.getConsciousness())) {
            throw new IllegalArgumentException("意识状态无效");
        }
        jdbcTemplate.update(
                "INSERT INTO elder_vital_measurement " +
                "(user_id,systolic_pressure,diastolic_pressure,heart_rate,spo2,temperature," +
                "respiratory_rate,supplemental_oxygen,consciousness,measured_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                userId, dto.getSystolicPressure(), dto.getDiastolicPressure(),
                dto.getHeartRate(), dto.getSpo2(), dto.getTemperature(), dto.getRespiratoryRate(),
                dto.getSupplementalOxygen() == null ? null : (dto.getSupplementalOxygen() ? 1 : 0),
                dto.getConsciousness(), Timestamp.valueOf(LocalDateTime.now())
        );
    }

    public List<Map<String, Object>> getHistory(Integer userId, Integer days) {
        int safeDays = days == null ? 30 : Math.max(1, Math.min(days, 90));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT systolic_pressure,diastolic_pressure,heart_rate,spo2,temperature," +
                "respiratory_rate,supplemental_oxygen,consciousness,measured_at " +
                "FROM elder_vital_measurement WHERE user_id=? AND measured_at>=? ORDER BY measured_at ASC",
                userId, Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays)));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("systolicPressure", row.get("systolic_pressure"));
            item.put("diastolicPressure", row.get("diastolic_pressure"));
            item.put("heartRate", row.get("heart_rate"));
            item.put("spo2", row.get("spo2"));
            item.put("temperature", row.get("temperature"));
            item.put("respiratoryRate", row.get("respiratory_rate"));
            Object oxygen = row.get("supplemental_oxygen");
            item.put("supplementalOxygen", oxygen == null ? null :
                    oxygen instanceof Boolean ? oxygen : ((Number) oxygen).intValue() == 1);
            item.put("consciousness", row.get("consciousness"));
            item.put("measuredAt", formatTime(row.get("measured_at")));
            history.add(item);
        }
        return history;
    }

    private Map<String, Object> profileForAi(Map<String, Object> dbProfile) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("age", dbProfile.get("age"));
        p.put("sex_male", dbProfile.get("sexMale"));
        p.put("bmi", dbProfile.get("bmi"));
        return p;
    }

    private Map<String, Object> measurementForAi(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measured_at", row.get("measuredAt"));
        m.put("systolic_pressure", row.get("systolicPressure"));
        m.put("diastolic_pressure", row.get("diastolicPressure"));
        m.put("heart_rate", row.get("heartRate"));
        m.put("spo2", row.get("spo2"));
        m.put("temperature", row.get("temperature"));
        m.put("respiratory_rate", row.get("respiratoryRate"));
        m.put("supplemental_oxygen", row.get("supplementalOxygen"));
        m.put("consciousness", row.get("consciousness"));
        return m;
    }

    public PersonalizedRiskResponse assess(Integer userId) {
        Map<String, Object> profile = getProfile(userId);
        if (profile == null) {
            throw new IllegalStateException("请先填写老人基础风险档案");
        }

        List<Map<String, Object>> history = getHistory(userId, 90);
        Map<String, Object> current = history.isEmpty() ? null : history.get(history.size() - 1);

        List<Map<String, Object>> aiHistory = new ArrayList<>();
        for (int i = 0; i < history.size() - 1; i++) {
            aiHistory.add(measurementForAi(history.get(i)));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("user_id", userId);
        request.put("profile", profileForAi(profile));
        request.put("current", current == null ? null : measurementForAi(current));
        request.put("history", aiHistory);

        PersonalizedRiskResponse result = riskModelClient.predict(request);

        jdbcTemplate.update(
                "INSERT INTO personalized_risk_assessment " +
                "(user_id,group_risk_probability,personal_change_score,baseline_available,risk_level," +
                "reasons_json,single_alerts_json,news2_complete,assessment_basis,model_name,model_version,assessment_time) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                userId,
                result.getGroupRiskProbability(),
                result.getPersonalChangeScore(),
                Boolean.TRUE.equals(result.getBaselineAvailable()) ? 1 : 0,
                result.getRiskLevel(),
                JSON.toJSONString(result.getReasons()),
                JSON.toJSONString(result.getSingleAlerts()),
                Boolean.TRUE.equals(result.getNews2Complete()) ? 1 : 0,
                result.getAssessmentBasis(),
                result.getModelName(),
                result.getModelVersion()
        );
        return result;
    }

    public Map<String, Object> latest(Integer userId) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT id,user_id,group_risk_probability,personal_change_score,baseline_available," +
                "risk_level,reasons_json,single_alerts_json,news2_complete,assessment_basis," +
                "model_name,model_version,assessment_time " +
                "FROM personalized_risk_assessment WHERE user_id=? " +
                "ORDER BY assessment_time DESC,id DESC LIMIT 1", userId);
        if (list.isEmpty()) {
            return null;
        }
        Map<String, Object> row = list.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("userId", row.get("user_id"));
        result.put("groupRiskProbability", row.get("group_risk_probability"));
        result.put("personalChangeScore", row.get("personal_change_score"));
        result.put("baselineAvailable", row.get("baseline_available"));
        result.put("riskLevel", row.get("risk_level"));
        result.put("reasonsJson", row.get("reasons_json"));
        Object alertJson = row.get("single_alerts_json");
        result.put("singleAlerts", alertJson == null ? Collections.emptyList() :
                JSON.parseArray(alertJson.toString(), String.class));
        result.put("news2Complete", row.get("news2_complete"));
        result.put("assessmentBasis", row.get("assessment_basis"));
        result.put("modelName", row.get("model_name"));
        result.put("modelVersion", row.get("model_version"));
        result.put("assessmentTime", formatTime(row.get("assessment_time")));
        return result;
    }

    private String formatTime(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(DISPLAY_TIME);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DISPLAY_TIME);
        }
        return value == null ? null : value.toString();
    }
}
