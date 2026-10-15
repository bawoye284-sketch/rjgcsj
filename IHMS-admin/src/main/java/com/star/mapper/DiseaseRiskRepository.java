package com.star.mapper;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.star.model.dto.disease.DiseaseProfileRequest;
import com.star.service.disease.DiseaseRiskContract;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class DiseaseRiskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public DiseaseRiskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public static String json(Object value) { return JSON.toJSONString(value, JSONWriter.Feature.WriteNulls); }

    public Map<String, Object> profile(int userId) {
        return transaction.execute(status -> loadProfile(userId));
    }

    private Map<String, Object> loadProfile(int userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM elder_disease_profile WHERE user_id=? FOR UPDATE", userId);
        Map<String, Object> profile = new LinkedHashMap<>(), history = new LinkedHashMap<>(), years = new LinkedHashMap<>();
        Map<String, Object> row = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
        DiseaseRiskContract.FIELDS.forEach((key, column) -> profile.put(key, row.get(column)));
        DiseaseRiskContract.CODES.forEach(code -> { history.put(code, null); years.put(code, null); });
        for (Map<String, Object> item : jdbc.queryForList("SELECT * FROM elder_disease_history WHERE user_id=?", userId)) {
            String code = item.get("disease_code").toString();
            if (history.containsKey(code)) { history.put(code, item.get("history_status")); years.put(code, item.get("diagnosed_year")); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile); result.put("diseaseHistory", history); result.put("diagnosisYears", years);
        result.put("revision", row.getOrDefault("profile_revision", 0L));
        result.put("updatedAt", row.isEmpty() ? null : row.get("updated_at").toString());
        return result;
    }

    public Map<String, Object> saveProfile(int userId, DiseaseProfileRequest request) {
        return transaction.execute(status -> {
            Map<String, Object> previous = loadProfile(userId);
            long revision = ((Number) previous.get("revision")).longValue();
            if (revision != request.getRevision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "档案已更新，请刷新后重试");
            List<Object> values = DiseaseRiskContract.FIELDS.keySet().stream().map(request.getProfile()::get).collect(Collectors.toList());
            String columns = String.join(",", DiseaseRiskContract.FIELDS.values());
            if (revision == 0) {
                List<Object> args = new ArrayList<>(); args.add(userId); args.addAll(values); args.add(1L);
                jdbc.update("INSERT INTO elder_disease_profile (user_id," + columns + ",profile_revision) VALUES ("
                        + String.join(",", Collections.nCopies(args.size(), "?")) + ")", args.toArray());
            } else {
                List<Object> args = new ArrayList<>(values); args.add(revision + 1); args.add(userId); args.add(revision);
                int changed = jdbc.update("UPDATE elder_disease_profile SET "
                                + DiseaseRiskContract.FIELDS.values().stream().map(c -> c + "=?").collect(Collectors.joining(","))
                                + ",profile_revision=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND profile_revision=?", args.toArray());
                if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "档案已更新，请刷新后重试");
            }
            // These are the five current answers, not immutable diagnosis events.
            jdbc.update("DELETE FROM elder_disease_history WHERE user_id=?", userId);
            for (String code : DiseaseRiskContract.CODES)
                jdbc.update("INSERT INTO elder_disease_history (user_id,disease_code,history_status,diagnosed_year) VALUES (?,?,?,?)",
                        userId, code, request.getDiseaseHistory().get(code), request.getDiagnosisYears().get(code));
            return loadProfile(userId);
        });
    }

    public Map<String, Object> byRequest(int userId, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM disease_prediction_assessment WHERE user_id=? AND request_id=?", userId, requestId);
        return rows.isEmpty() ? null : assessment(userId, rows.get(0).get("id").toString());
    }

    public Map<String, Object> saveAssessment(int userId, String requestId, long revision, String hash,
            Map<String, Object> snapshot, Map<String, Object> modelSummary, Map<String, Object> response) {
        String id = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            boolean allPredicted = results.stream().allMatch(r -> "predicted".equals(r.get("status")));
            jdbc.update("INSERT INTO disease_prediction_assessment "
                            + "(id,user_id,request_id,profile_revision,input_snapshot_json,input_sha256,model_summary_json,model_version,research_only,validation_status,assessment_status,assessed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", id, userId, requestId, revision, json(snapshot), hash,
                    json(modelSummary), response.get("model_version"), 1, response.get("validation_status"),
                    allPredicted ? "completed" : "completed_with_skips", Timestamp.valueOf(LocalDateTime.now()));
            for (Map<String, Object> item : results) {
                jdbc.update("INSERT INTO disease_prediction_result "
                                + "(assessment_id,disease_code,disease_name,status,probability,threshold,threshold_policy,alert,risk_band,reason,missing_fields_json,outcome_definition,horizon_definition) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", id, item.get("disease_code"), item.get("disease_name"), item.get("status"),
                        item.get("probability"), item.get("threshold"), item.get("threshold_policy"),
                        item.get("alert") == null ? null : Boolean.TRUE.equals(item.get("alert")) ? 1 : 0,
                        item.get("risk_band"), item.get("reason"), json(item.get("missing_fields")),
                        item.get("outcome_definition"), item.get("horizon_definition"));
            }
            return assessment(userId, id);
        });
    }

    private Map<String, Object> header(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id").toString()); result.put("requestId", row.get("request_id"));
        result.put("profileRevision", row.get("profile_revision")); result.put("modelVersion", row.get("model_version"));
        result.put("researchOnly", true); result.put("validationStatus", row.get("validation_status"));
        result.put("status", row.get("assessment_status")); result.put("assessedAt", row.get("assessed_at").toString());
        return result;
    }

    public Map<String, Object> assessment(int userId, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM disease_prediction_assessment WHERE id=? AND user_id=?", id, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评估记录不存在");
        Map<String, Object> row = rows.get(0), result = header(row);
        result.put("inputSnapshot", JSON.parseObject(row.get("input_snapshot_json").toString()));
        result.put("modelSummary", JSON.parseObject(row.get("model_summary_json").toString()));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Map<String, Object> detail : jdbc.queryForList("SELECT * FROM disease_prediction_result WHERE assessment_id=? ORDER BY id", id)) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : Arrays.asList("disease_code", "disease_name", "status", "probability", "threshold", "threshold_policy", "risk_band", "reason", "outcome_definition", "horizon_definition"))
                item.put(key, detail.get(key));
            item.put("alert", detail.get("alert") == null ? null : ((Number) detail.get("alert")).intValue() == 1);
            item.put("missing_fields", JSON.parseArray(detail.get("missing_fields_json").toString()));
            outcomes.add(item);
        }
        result.put("results", outcomes); return result;
    }

    public Map<String, Object> latest(int userId) {
        List<String> ids = jdbc.query("SELECT id FROM disease_prediction_assessment WHERE user_id=? ORDER BY assessed_at DESC,id DESC LIMIT 1",
                (rs, index) -> rs.getString(1), userId);
        return ids.isEmpty() ? null : assessment(userId, ids.get(0));
    }

    public Map<String, Object> history(int userId, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 50) throw new IllegalArgumentException("分页范围无效");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM disease_prediction_assessment WHERE user_id=?", Long.class, userId));
        result.put("page", page); result.put("pageSize", size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM disease_prediction_assessment WHERE user_id=? ORDER BY assessed_at DESC,id DESC LIMIT ? OFFSET ?",
                userId, size, (page - 1) * size);
        result.put("items", rows.stream().map(this::header).collect(Collectors.toList())); return result;
    }
}
