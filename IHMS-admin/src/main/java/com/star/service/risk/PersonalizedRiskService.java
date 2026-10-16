
package com.star.service.risk;

import com.alibaba.fastjson2.JSON;
import com.star.model.dto.risk.ElderRiskProfileDto;
import com.star.model.dto.risk.CurrentRiskAssessmentDto;
import com.star.model.dto.risk.PersonalizedRiskResponse;
import com.star.model.dto.risk.VitalMeasurementDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
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

    @Resource
    private PlatformTransactionManager transactionManager;

    private void validateBasicProfile(ElderRiskProfileDto profile) {
        if (profile == null || profile.getAge() == null || profile.getAge() < 0 || profile.getAge() > 110
                || profile.getSexMale() == null || (profile.getSexMale() != 0 && profile.getSexMale() != 1)
                || profile.getBmi() == null || !Double.isFinite(profile.getBmi()) || profile.getBmi() < 10 || profile.getBmi() > 80)
            throw new IllegalArgumentException("个性化评估需要有效年龄、性别和10～80范围的BMI");
    }

    public void saveBasicProfile(Integer userId, ElderRiskProfileDto profile) {
        validateBasicProfile(profile);
        if (getProfile(userId) == null) {
            // Unused legacy columns retain their database defaults; they are not disease-history answers.
            jdbcTemplate.update("INSERT INTO elder_risk_profile (user_id,age,sex_male,bmi) VALUES (?,?,?,?)",
                    userId, profile.getAge(), profile.getSexMale(), profile.getBmi());
        } else {
            jdbcTemplate.update("UPDATE elder_risk_profile SET age=?,sex_male=?,bmi=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                    profile.getAge(), profile.getSexMale(), profile.getBmi(), userId);
        }
    }

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
        saveResult(userId, result, null, null);
        return result;
    }

    public PersonalizedRiskResponse assessCurrent(Integer userId, CurrentRiskAssessmentDto input) {
        if (input == null) throw new IllegalArgumentException("请提供汇总表资料");
        validateBasicProfile(input.getProfile());
        VitalMeasurementDto measurement = input.getMeasurement();
        if (measurement == null) throw new IllegalArgumentException("请填写至少一项本次实测指标");
        Double[] values = { measurement.getSystolicPressure(), measurement.getDiastolicPressure(), measurement.getHeartRate(),
                measurement.getSpo2(), measurement.getTemperature(), measurement.getRespiratoryRate() };
        double[] minimums = {40,20,30,60,32,1}, maximums = {300,200,220,100,43,80};
        boolean any = false;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) continue;
            any = true;
            if (!Double.isFinite(values[i]) || values[i] < minimums[i] || values[i] > maximums[i])
                throw new IllegalArgumentException("本次测量数值超出有效范围");
        }
        if (!any) throw new IllegalArgumentException("请填写至少一项本次实测指标，未测的项目可以留空");
        if (measurement.getSupplementalOxygen() != null || measurement.getConsciousness() != null)
            throw new IllegalArgumentException("联合评估当前只使用六项数值测量");
        LocalDateTime now = LocalDateTime.now();
        measurement.setMeasuredAt(DISPLAY_TIME.format(now));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("measured_at", now.toString());
        String[] names = {"systolic_pressure","diastolic_pressure","heart_rate","spo2","temperature","respiratory_rate"};
        for (int i = 0; i < names.length; i++) current.put(names[i], values[i]);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("age", input.getProfile().getAge()); profile.put("sexMale", input.getProfile().getSexMale());
        profile.put("bmi", input.getProfile().getBmi());
        String requestId=input.getRequestId();
        if (requestId!=null && !requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("requestId必须为UUID");
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("profile",profile);
        Map<String,Object> captured=new LinkedHashMap<>();
        String[] displayNames={"systolicPressure","diastolicPressure","heartRate","spo2","temperature","respiratoryRate"};
        for(int i=0;i<displayNames.length;i++) captured.put(displayNames[i],values[i]);
        snapshot.put("measurement",captured);
        PersonalizedRiskResponse existing=replay(userId,requestId,snapshot);
        if(existing!=null) return existing;
        snapshot.put("measuredAt",DISPLAY_TIME.format(now));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("user_id", userId); request.put("profile", profileForAi(profile)); request.put("current", current);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> row : getHistory(userId, 90)) history.add(measurementForAi(row));
        request.put("history", history);
        snapshot.put("history", history);
        // Network work precedes the short write transaction, and uses this form's captured inputs.
        PersonalizedRiskResponse result = riskModelClient.predict(request);
        result.setAssessmentTime(DISPLAY_TIME.format(LocalDateTime.now()));
        try { return new TransactionTemplate(transactionManager).execute(status -> {
            saveBasicProfile(userId, input.getProfile());
            jdbcTemplate.update("INSERT INTO elder_vital_measurement (user_id,systolic_pressure,diastolic_pressure,heart_rate,spo2,temperature,respiratory_rate,measured_at) VALUES (?,?,?,?,?,?,?,?)",
                    userId, values[0], values[1], values[2], values[3], values[4], values[5], Timestamp.valueOf(now));
            saveResult(userId, result, requestId, JSON.toJSONString(snapshot,com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
            return result;
        }); } catch (DuplicateKeyException conflict) {
            PersonalizedRiskResponse saved=replay(userId,requestId,snapshot);
            if(saved==null) throw conflict;
            return saved;
        }
    }

    private PersonalizedRiskResponse replay(Integer userId,String requestId,Map<String,Object> snapshot) {
        if(requestId==null) return null;
        List<Long> ids=jdbcTemplate.query("SELECT id FROM personalized_risk_assessment WHERE user_id=? AND request_id=?",(rs,n)->rs.getLong(1),userId,requestId);
        if(ids.isEmpty()) return null;
        Map<String,Object> saved=assessment(userId,ids.get(0));
        com.alibaba.fastjson2.JSONObject old=(com.alibaba.fastjson2.JSONObject)saved.get("inputSnapshot");
        com.alibaba.fastjson2.JSONObject current=JSON.parseObject(JSON.toJSONString(snapshot,com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
        if(old==null || !Objects.equals(old.get("profile"),current.get("profile")) || !Objects.equals(old.get("measurement"),current.get("measurement")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该requestId已用于不同的个性化输入，请重新发起评估");
        return JSON.parseObject(JSON.toJSONString(saved),PersonalizedRiskResponse.class);
    }

    private void saveResult(Integer userId, PersonalizedRiskResponse result,String requestId,String snapshot) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO personalized_risk_assessment " +
                "(user_id,group_risk_probability,personal_change_score,baseline_available,risk_level," +
                "reasons_json,single_alerts_json,news2_complete,assessment_basis,model_name,model_version,request_id,input_snapshot_json,assessment_time) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",new String[]{"id"});
            Object[] values={
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
                result.getModelVersion(),requestId,snapshot};
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },keys);
        result.setAssessmentId(keys.getKey().longValue());result.setRequestId(requestId);
    }

    public Map<String, Object> latest(Integer userId) {
        List<Long> ids=jdbcTemplate.query("SELECT id FROM personalized_risk_assessment WHERE user_id=? ORDER BY assessment_time DESC,id DESC LIMIT 1",(rs,n)->rs.getLong(1),userId);
        return ids.isEmpty()?null:assessment(userId,ids.get(0));
    }

    public Map<String,Object> assessment(Integer userId,Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM personalized_risk_assessment WHERE user_id=? AND id=?", userId,id);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"个性化评估记录不存在");
        }
        Map<String, Object> row = list.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("assessmentId",row.get("id"));result.put("requestId",row.get("request_id"));
        Object snapshot=row.get("input_snapshot_json");result.put("inputSnapshot",snapshot==null?null:JSON.parseObject(snapshot.toString()));
        result.put("userId", row.get("user_id"));
        result.put("groupRiskProbability", row.get("group_risk_probability"));
        result.put("personalChangeScore", row.get("personal_change_score"));
        result.put("baselineAvailable", row.get("baseline_available"));
        result.put("riskLevel", row.get("risk_level"));
        result.put("reasonsJson", row.get("reasons_json"));
        result.put("reasons",row.get("reasons_json")==null?Collections.emptyList():JSON.parseArray(row.get("reasons_json").toString()));
        Object alertJson = row.get("single_alerts_json");
        result.put("singleAlerts", alertJson == null ? Collections.emptyList() :
                JSON.parseArray(alertJson.toString(), String.class));
        result.put("news2Complete", row.get("news2_complete"));
        result.put("assessmentBasis", row.get("assessment_basis"));
        result.put("modelName", row.get("model_name"));
        result.put("modelVersion", row.get("model_version"));
        result.put("assessmentTime", formatTime(row.get("assessment_time")));
        result.put("assessmentAt",formatTime(row.get("assessment_time")));
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
