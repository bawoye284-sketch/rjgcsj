package com.star.service.health;

import com.star.mapper.DiseaseRiskRepository;
import com.star.model.dto.risk.HealthReviewQuery;
import com.star.service.disease.DiseaseRiskContract;
import com.star.service.disease.DiseaseRiskModelClient;
import com.star.service.risk.PersonalizedRiskService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Shared reads: every role sees the same stored records, after ownership/consultation checks. */
@Service
public class HealthReviewService {
    private final JdbcTemplate jdbc;
    private final HealthAccessService access;
    private final DiseaseRiskRepository diseases;
    private final PersonalizedRiskService personal;
    private final DiseaseRiskModelClient models;
    public HealthReviewService(JdbcTemplate jdbc, HealthAccessService access, DiseaseRiskRepository diseases,
                               PersonalizedRiskService personal, DiseaseRiskModelClient models) {
        this.jdbc=jdbc; this.access=access; this.diseases=diseases; this.personal=personal; this.models=models;
    }
    private void validate(HealthReviewQuery q) {
        if (q.getPage()<1 || q.getPage()>100000 || q.getPageSize()<1 || q.getPageSize()>50) throw new IllegalArgumentException("分页范围无效");
        if (q.getUserId()!=null && q.getUserId()<1) throw new IllegalArgumentException("用户ID无效");
        if (q.getKeyword()!=null && q.getKeyword().length()>100) throw new IllegalArgumentException("搜索词过长");
        if (q.getKind()!=null && !Arrays.asList("", "all", "personalized", "chronic").contains(q.getKind())) throw new IllegalArgumentException("评估类型无效");
        if (q.getDiseaseCode()!=null && !q.getDiseaseCode().isEmpty() && !DiseaseRiskContract.CODES.contains(q.getDiseaseCode())) throw new IllegalArgumentException("病种无效");
        if (q.getStatus()!=null && !Arrays.asList("", "completed", "completed_with_skips", "predicted", "already_diagnosed", "insufficient_data", "not_applicable", "model_unavailable").contains(q.getStatus())) throw new IllegalArgumentException("状态无效");
        try {
            if (present(q.getFrom())) LocalDate.parse(q.getFrom());
            if (present(q.getTo())) LocalDate.parse(q.getTo());
            if (present(q.getFrom()) && present(q.getTo()) && q.getFrom().compareTo(q.getTo())>0) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        } catch (java.time.format.DateTimeParseException error) { throw new IllegalArgumentException("日期格式应为YYYY-MM-DD"); }
    }
    private boolean present(String value) { return value!=null && !value.isEmpty(); }
    private Map<String,Object> normalize(Map<String,Object> row) {
        List<String> names=Arrays.asList("userId","userName","userAccount","assessedAt","modelVersion","riskLevel","systolicPressure","diastolicPressure","heartRate","respiratoryRate","measuredAt","lastMeasuredAt","lastChronicAt","lastPersonalizedAt","diseaseCode","thresholdReached","recordedAt");
        Map<String,Object> out=new LinkedHashMap<>();
        row.forEach((key,value)->{
            String canonical=names.stream().filter(n->n.equalsIgnoreCase(key)).findFirst().orElse(key);
            Object converted=value instanceof Timestamp?((Timestamp)value).toLocalDateTime().toString().replace('T',' '):value instanceof LocalDateTime?value.toString().replace('T',' '):value;
            if("id".equals(key) && converted instanceof String) converted=((String)converted).trim();
            out.put(canonical,converted);
        });return out;
    }
    private String people(HealthReviewQuery q, String column, List<Object> args) {
        String where=access.scope(column,args);
        if (q.getUserId()!=null) { where+=" AND "+column+"=?"; args.add(q.getUserId()); }
        if (present(q.getKeyword())) { where+=" AND (u.user_name LIKE ? OR u.user_account LIKE ?)"; args.add("%"+q.getKeyword().trim()+"%");args.add("%"+q.getKeyword().trim()+"%"); }
        return where;
    }
    private String dates(HealthReviewQuery q,String column,List<Object> args) {
        String where="";
        if (present(q.getFrom())) { where+=" AND "+column+">=?"; args.add(Timestamp.valueOf(LocalDate.parse(q.getFrom()).atStartOfDay())); }
        if (present(q.getTo())) { where+=" AND "+column+"<?"; args.add(Timestamp.valueOf(LocalDate.parse(q.getTo()).plusDays(1).atStartOfDay())); }
        return where;
    }
    private Map<String,Object> page(String select,String where,String order,List<Object> args,HealthReviewQuery q) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM ("+select+where+") health_count",Long.class,args.toArray()));
        List<Object> paged=new ArrayList<>(args);paged.add(q.getPageSize());paged.add((q.getPage()-1)*q.getPageSize());
        List<Map<String,Object>> rows=jdbc.queryForList(select+where+order+" LIMIT ? OFFSET ?",paged.toArray());
        rows.replaceAll(this::normalize);
        out.put("items",rows);out.put("page",q.getPage());out.put("pageSize",q.getPageSize());return out;
    }
    public Map<String,Object> patients(HealthReviewQuery q) {
        access.requireReviewer();validate(q);List<Object> args=new ArrayList<>();
        String where=" WHERE "+people(q,"u.id",args);
        if (access.role()==1) where+=" AND (u.user_role=2 OR EXISTS (SELECT 1 FROM elder_disease_profile cp WHERE cp.user_id=u.id) OR EXISTS (SELECT 1 FROM elder_risk_profile bp WHERE bp.user_id=u.id))";
        String select="SELECT u.id AS userId,u.user_name AS userName,u.user_account AS userAccount,"
                +"(SELECT MAX(measured_at) FROM elder_vital_measurement v WHERE v.user_id=u.id) AS lastMeasuredAt,"
                +"(SELECT MAX(assessed_at) FROM disease_prediction_assessment d WHERE d.user_id=u.id) AS lastChronicAt,"
                +"(SELECT MAX(assessment_time) FROM personalized_risk_assessment p WHERE p.user_id=u.id) AS lastPersonalizedAt FROM `user` u";
        return page(select,where," ORDER BY u.id DESC",args,q);
    }
    public Map<String,Object> assessments(HealthReviewQuery q) {
        validate(q);List<Object> args=new ArrayList<>();
        String personalized="SELECT CAST(p.id AS CHAR(36)) AS id,'personalized' AS kind,p.user_id AS userId,u.user_name AS userName,u.user_account AS userAccount,p.assessment_time AS assessedAt,p.model_version AS modelVersion,'completed' AS status,p.risk_level AS riskLevel FROM personalized_risk_assessment p JOIN `user` u ON u.id=p.user_id WHERE "+people(q,"p.user_id",args)+dates(q,"p.assessment_time",args);
        if ("chronic".equals(q.getKind()) || present(q.getDiseaseCode()) || (present(q.getStatus()) && !"completed".equals(q.getStatus()))) personalized+=" AND 1=0";
        String chronic="SELECT d.id AS id,'chronic' AS kind,d.user_id AS userId,u.user_name AS userName,u.user_account AS userAccount,d.assessed_at AS assessedAt,d.model_version AS modelVersion,d.assessment_status AS status,NULL AS riskLevel FROM disease_prediction_assessment d JOIN `user` u ON u.id=d.user_id WHERE "+people(q,"d.user_id",args)+dates(q,"d.assessed_at",args);
        if ("personalized".equals(q.getKind())) chronic+=" AND 1=0";
        if (present(q.getDiseaseCode()) || (present(q.getStatus()) && !q.getStatus().startsWith("completed"))) {
            chronic+=" AND EXISTS (SELECT 1 FROM disease_prediction_result r WHERE r.assessment_id=d.id";
            if (present(q.getDiseaseCode())) { chronic+=" AND r.disease_code=?";args.add(q.getDiseaseCode()); }
            if (present(q.getStatus()) && !q.getStatus().startsWith("completed")) { chronic+=" AND r.status=?";args.add(q.getStatus()); }
            chronic+=")";
        }
        if (present(q.getStatus()) && q.getStatus().startsWith("completed")) { chronic+=" AND d.assessment_status=?";args.add(q.getStatus()); }
        return page("SELECT * FROM ("+personalized+" UNION ALL "+chronic+") health_rows",""," ORDER BY assessedAt DESC,CASE WHEN kind='personalized' THEN LENGTH(TRIM(id)) ELSE 0 END DESC,id DESC",args,q);
    }
    public Map<String,Object> measurements(HealthReviewQuery q) {
        validate(q);List<Object> args=new ArrayList<>();
        String where=" WHERE "+people(q,"v.user_id",args)+dates(q,"v.measured_at",args);
        String select="SELECT v.id,v.user_id AS userId,u.user_name AS userName,u.user_account AS userAccount,v.systolic_pressure AS systolicPressure,v.diastolic_pressure AS diastolicPressure,v.heart_rate AS heartRate,v.spo2,v.temperature,v.respiratory_rate AS respiratoryRate,v.measured_at AS measuredAt FROM elder_vital_measurement v JOIN `user` u ON u.id=v.user_id";
        return page(select,where," ORDER BY v.measured_at DESC,v.id DESC",args,q);
    }
    public Map<String,Object> overview(int userId,int days) {
        access.patient(userId);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("patient",normalize(jdbc.queryForMap("SELECT id AS userId,user_name AS userName,user_account AS userAccount FROM `user` WHERE id=?",userId)));
        out.put("profile",diseases.profile(userId));out.put("basicProfile",personal.getProfile(userId));
        out.put("latestPersonalized",personal.latest(userId));out.put("latestChronic",diseases.latest(userId));
        int safeDays=Math.max(1,Math.min(90,days));out.put("days",safeDays);out.put("vitals",personal.getHistory(userId,safeDays));
        List<Map<String,Object>> records=jdbc.queryForList("SELECT h.id,c.name,c.unit,h.value,h.create_time AS recordedAt FROM user_health h LEFT JOIN health_model_config c ON c.id=h.health_model_config_id WHERE h.user_id=? AND h.create_time>=? ORDER BY h.create_time DESC,h.id DESC LIMIT 200",userId,Timestamp.valueOf(LocalDateTime.now().minusDays(safeDays)));
        records.replaceAll(this::normalize);out.put("healthRecords",records);
        return out;
    }
    public Map<String,Object> detail(String kind,String id) {
        String table,column;
        if ("chronic".equals(kind)) { table="disease_prediction_assessment";column="id"; }
        else if ("personalized".equals(kind) && id.matches("[0-9]{1,18}")) { table="personalized_risk_assessment";column="id"; }
        else throw new IllegalArgumentException("评估类型或编号无效");
        List<Integer> owners=jdbc.query("SELECT user_id FROM "+table+" WHERE "+column+"=?",(rs,n)->rs.getInt(1),id);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"评估记录不存在");
        int owner=owners.get(0);access.patient(owner);
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind",kind);out.put("userId",owner);
        out.put("assessment","chronic".equals(kind)?diseases.assessment(owner,id):personal.assessment(owner,Long.parseLong(id)));
        return out;
    }
    public Map<String,Object> statistics(HealthReviewQuery q) {
        validate(q);Map<String,Object> out=new LinkedHashMap<>();
        String[][] tables={{"patients","`user`","u.id",""},{"measurements","elder_vital_measurement a JOIN `user` u ON u.id=a.user_id","a.user_id","a.measured_at"},{"personalized","personalized_risk_assessment a JOIN `user` u ON u.id=a.user_id","a.user_id","a.assessment_time"},{"chronic","disease_prediction_assessment a JOIN `user` u ON u.id=a.user_id","a.user_id","a.assessed_at"}};
        for(String[] t:tables) {
            List<Object> args=new ArrayList<>();String from=t[0].equals("patients")?"`user` u":t[1];
            String where=people(q,t[2],args)+(t[3].isEmpty()?"":dates(q,t[3],args));
            if (t[0].equals("patients") && access.role()==1) where+=" AND (u.user_role=2 OR EXISTS (SELECT 1 FROM elder_disease_profile cp WHERE cp.user_id=u.id) OR EXISTS (SELECT 1 FROM elder_risk_profile bp WHERE bp.user_id=u.id))";
            out.put(t[0],jdbc.queryForObject("SELECT COUNT(*) FROM "+from+" WHERE "+where,Long.class,args.toArray()));
        }
        List<Object> args=new ArrayList<>();String where=people(q,"d.user_id",args)+dates(q,"d.assessed_at",args);
        out.put("diseases",jdbc.queryForList("SELECT r.disease_code AS diseaseCode,COUNT(*) AS total,SUM(CASE WHEN r.status='predicted' THEN 1 ELSE 0 END) AS predicted,SUM(CASE WHEN r.status='predicted' AND r.alert=1 THEN 1 ELSE 0 END) AS thresholdReached,SUM(CASE WHEN r.status<>'predicted' THEN 1 ELSE 0 END) AS skipped FROM disease_prediction_result r JOIN disease_prediction_assessment d ON d.id=r.assessment_id JOIN `user` u ON u.id=d.user_id WHERE "+where+" GROUP BY r.disease_code",args.toArray()));
        ((List<Map<String,Object>>)out.get("diseases")).replaceAll(this::normalize);
        out.put("updatedAt",LocalDateTime.now().toString());return out;
    }
    public Map<String,Object> models() { access.role();return models.models(); }
}
