package com.star.service.doctor;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoctorPatientHealthService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private com.star.service.health.HealthReviewService healthReview;

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

        Map<String,Object> overview = healthReview.overview(patientId, days == null ? 30 : days);
        overview.put("patient", matched.get(0));
        overview.put("latestRisk", overview.get("latestPersonalized"));
        java.util.Collections.reverse((List<?>) overview.get("vitals"));
        return overview;
    }

    private String formatTime(Timestamp time) {
        return time == null ? null : time.toLocalDateTime().format(TIME_FORMAT);
    }
}
