package com.star.service.disease;

import com.star.model.dto.disease.DiseaseProfileRequest;
import java.time.Year;
import java.util.*;

/** Shared, explicit wire mapping; unknown is never silently converted to no. */
public final class DiseaseRiskContract {
    public static final List<String> CODES = Collections.unmodifiableList(Arrays.asList(
            "hypertension", "diabetes", "stroke", "heart_attack", "osteoporosis"));
    public static final LinkedHashMap<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("age", "age"); FIELDS.put("sexMale", "sex_male");
        FIELDS.put("weightKg", "weight_kg"); FIELDS.put("selfRatedHealth", "self_rated_health");
        FIELDS.put("smokingCurrent", "smoking_current"); FIELDS.put("drinkingAny", "drinking_any");
        FIELDS.put("exerciseRegular", "exercise_regular");
        FIELDS.put("highCholesterolHistory", "high_cholesterol_history");
        FIELDS.put("healthWorsened", "health_worsened"); FIELDS.put("weightGain10lb", "weight_gain_10lb");
        FIELDS.put("weightLoss10lb", "weight_loss_10lb"); FIELDS.put("hipFractureHistory", "hip_fracture_history");
    }
    private DiseaseRiskContract() { }

    public static void validate(DiseaseProfileRequest request) {
        if (request == null || request.getRevision() == null || request.getRevision() < 0
                || request.getProfile() == null || request.getDiseaseHistory() == null)
            throw new IllegalArgumentException("请提供资料、五项病史和档案版本");
        if (!FIELDS.keySet().containsAll(request.getProfile().keySet()))
            throw new IllegalArgumentException("健康资料包含不支持的字段");
        if (!new HashSet<>(CODES).equals(request.getDiseaseHistory().keySet()))
            throw new IllegalArgumentException("需要完整的五项疾病史，可用null表示不清楚");
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String field : FIELDS.keySet()) {
            Object value = request.getProfile().get(field);
            if (value == null) { clean.put(field, null); continue; }
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
                throw new IllegalArgumentException("资料字段必须是有效数字：" + field);
            double number = ((Number) value).doubleValue();
            double min = field.equals("age") ? 0 : field.equals("weightKg") ? 25 : field.equals("selfRatedHealth") ? 1 : 0;
            double max = field.equals("age") ? 110 : field.equals("weightKg") ? 320 : field.equals("selfRatedHealth") ? 5 : 1;
            if (number < min || number > max || (!field.equals("weightKg") && number != Math.rint(number)))
                throw new IllegalArgumentException("资料数值超出范围：" + field);
            if (field.equals("weightKg")) clean.put(field, number); else clean.put(field, (int) number);
        }
        // Age is needed for eligibility, while incomplete optional information
        // may still be saved and receives a per-disease explanation at assessment.
        if (clean.get("age") == null) throw new IllegalArgumentException("请填写年龄");
        if (Objects.equals(clean.get("weightGain10lb"), 1) && Objects.equals(clean.get("weightLoss10lb"), 1))
            throw new IllegalArgumentException("过去一年不能同时增重和减重至少10磅");
        Map<String, Object> years = request.getDiagnosisYears() == null ? new LinkedHashMap<>() : request.getDiagnosisYears();
        if (!CODES.containsAll(years.keySet())) throw new IllegalArgumentException("诊断年份包含未知病种");
        Map<String, Object> normalizedYears = new LinkedHashMap<>(), normalizedHistory = new LinkedHashMap<>();
        for (String code : CODES) {
            Integer status = integer(request.getDiseaseHistory().get(code), "疾病史"), year = integer(years.get(code), "诊断年份");
            if (status != null && status != 0 && status != 1) throw new IllegalArgumentException("病史只能为是、否或不清楚");
            if (year != null && (!Objects.equals(status, 1) || year < 1900 || year > Year.now().getValue()))
                throw new IllegalArgumentException("诊断年份无效：" + code);
            normalizedYears.put(code, year);
            normalizedHistory.put(code, status);
        }
        request.setProfile(clean); request.setDiseaseHistory(normalizedHistory); request.setDiagnosisYears(normalizedYears);
    }

    private static Integer integer(Object value, String field) {
        if (value == null) return null;
        if (!(value instanceof Number)) throw new IllegalArgumentException(field + "必须是整数或null");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new IllegalArgumentException(field + "必须是整数或null");
        return (int) number;
    }

    public static Map<String, Object> pythonRequest(Map<String, Object> snapshot) {
        Map<String, Object> profile = (Map<String, Object>) snapshot.get("profile");
        Map<String, Object> translated = new LinkedHashMap<>(), result = new LinkedHashMap<>();
        FIELDS.forEach((name, wire) -> translated.put(wire, profile.get(name)));
        result.put("profile", translated); result.put("disease_history", snapshot.get("diseaseHistory"));
        return result;
    }
}
