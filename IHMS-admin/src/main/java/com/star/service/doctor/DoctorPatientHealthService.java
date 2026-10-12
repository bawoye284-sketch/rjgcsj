package com.star.service.doctor;

import com.alibaba.fastjson2.JSON;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoctorPatientHealthService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> patients(Integer doctorId) {
        return jdbcTemplate.query(
                "SELECT u.id AS patient_id,u.user_name,u.user_account,MAX(c.create_time) AS last_consultation_at " +
                        "FROM doctor_consultation c JOIN `user` u ON u.id=c.patient_id " +
                        "WHERE c.doctor_id=? GROUP BY u.id,u.user_name,u.user_account " +
                        "ORDER BY u.user_name,u.id",
                (rs, rowNum) -> {
                    Map<String, Object> patient = new LinkedHashMap<>();
                    patient.put("patientId", rs.getInt("patient_id"));
                    patient.put("patientName", rs.getString("user_name"));
                    patient.put("account", rs.getString("user_account"));
                    patient.put("lastConsultationAt", formatTime(rs.getTimestamp("last_consultation_at")));
                    return patient;
                }, doctorId);
    }

    public Map<String, Object> health(Integer doctorId, Integer patientId, Integer days) {
        List<Map<String, Object>> matched = jdbcTemplate.query(
                "SELECT u.id,u.user_name,u.user_account FROM `user` u " +
                        "WHERE u.id=? AND EXISTS (SELECT 1 FROM doctor_consultation c " +
                        "WHERE c.patient_id=u.id AND c.doctor_id=?)",
                (rs, rowNum) -> {
                    Map<String, Object> patient = new LinkedHashMap<>();
                    patient.put("patientId", rs.getInt("id"));
                    patient.put("patientName", rs.getString("user_name"));
                    patient.put("account", rs.getString("user_account"));
                    return patient;
                }, patientId, doctorId);
        if (matched.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无法查看非本人患者的数据");
        }

        int safeDays = days == null ? 30 : Math.max(1, Math.min(days, 90));
        Timestamp start = Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays));

        List<Map<String, Object>> vitals = jdbcTemplate.query(
                "SELECT systolic_pressure,diastolic_pressure,heart_rate,spo2,temperature," +
                        "respiratory_rate,supplemental_oxygen,consciousness,measured_at " +
                        "FROM elder_vital_measurement WHERE user_id=? AND measured_at>=? " +
                        "ORDER BY measured_at DESC,id DESC LIMIT 200",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("systolicPressure", rs.getBigDecimal("systolic_pressure"));
                    item.put("diastolicPressure", rs.getBigDecimal("diastolic_pressure"));
                    item.put("heartRate", rs.getBigDecimal("heart_rate"));
                    item.put("spo2", rs.getBigDecimal("spo2"));
                    item.put("temperature", rs.getBigDecimal("temperature"));
                    item.put("respiratoryRate", rs.getBigDecimal("respiratory_rate"));
                    Object oxygen = rs.getObject("supplemental_oxygen");
                    item.put("supplementalOxygen", oxygen == null ? null :
                            oxygen instanceof Boolean ? oxygen : ((Number) oxygen).intValue() == 1);
                    item.put("consciousness", rs.getString("consciousness"));
                    item.put("measuredAt", formatTime(rs.getTimestamp("measured_at")));
                    return item;
                }, patientId, start);

        List<Map<String, Object>> records = jdbcTemplate.query(
                "SELECT uh.id,h.name,h.unit,uh.value,uh.create_time FROM user_health uh " +
                        "LEFT JOIN health_model_config h ON h.id=uh.health_model_config_id " +
                        "WHERE uh.user_id=? AND uh.create_time>=? " +
                        "ORDER BY uh.create_time DESC,uh.id DESC LIMIT 200",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("name", rs.getString("name"));
                    item.put("unit", rs.getString("unit"));
                    item.put("value", rs.getString("value"));
                    item.put("recordedAt", formatTime(rs.getTimestamp("create_time")));
                    return item;
                }, patientId, start);

        List<Map<String, Object>> riskRows = jdbcTemplate.query(
                "SELECT risk_level,group_risk_probability,personal_change_score,single_alerts_json,assessment_time " +
                        "FROM personalized_risk_assessment WHERE user_id=? AND assessment_basis='NUMERIC5_AGGREGATE_HISTORY_V1' " +
                        "ORDER BY assessment_time DESC,id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Map<String, Object> risk = new LinkedHashMap<>();
                    risk.put("riskLevel", rs.getString("risk_level"));
                    risk.put("groupRiskProbability", rs.getBigDecimal("group_risk_probability"));
                    risk.put("personalChangeScore", rs.getBigDecimal("personal_change_score"));
                    String alerts = rs.getString("single_alerts_json");
                    risk.put("singleAlerts", alerts == null ? java.util.Collections.emptyList() :
                            JSON.parseArray(alerts, String.class));
                    risk.put("assessmentAt", formatTime(rs.getTimestamp("assessment_time")));
                    return risk;
                }, patientId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patient", matched.get(0));
        result.put("days", safeDays);
        result.put("vitals", vitals);
        result.put("healthRecords", records);
        result.put("latestRisk", riskRows.isEmpty() ? null : riskRows.get(0));
        return result;
    }

    private String formatTime(Timestamp time) {
        return time == null ? null : time.toLocalDateTime().format(TIME_FORMAT);
    }
}
