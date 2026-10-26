package com.star.service.notification;

import com.alibaba.fastjson2.JSON;
import com.star.context.LocalThreadHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@Service
public class DoctorAlertService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final String HANDLING="COALESCE((SELECT ha.action FROM health_alert_action ha WHERE ha.event_id=e.id AND ha.phase=e.level ORDER BY ha.id DESC LIMIT 1),'OPEN')";
    public DoctorAlertService(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
        // Re-check grants after acquiring the patient lock, including on MySQL REPEATABLE READ defaults.
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public static int doctor() {
        if(!Integer.valueOf(3).equals(LocalThreadHolder.getRoleId()) || LocalThreadHolder.getUserId()==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅医生可使用患者预警管理");
        return LocalThreadHolder.getUserId();
    }
    public boolean allowed(int user,int doctor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM health_doctor_authorization a JOIN doctor d ON d.id=a.doctor_id WHERE a.user_id=? AND a.doctor_id=? AND a.authorized=1 AND d.status=1 AND EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=a.user_id AND c.doctor_id=a.doctor_id AND c.status=0)",Integer.class,user,doctor)>0;
    }
    public List<Integer> recipients(int user) {
        return jdbc.query("SELECT a.doctor_id FROM health_doctor_authorization a JOIN doctor d ON d.id=a.doctor_id WHERE a.user_id=? AND a.authorized=1 AND d.status=1 AND EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=a.user_id AND c.doctor_id=a.doctor_id AND c.status=0)",(rs,n)->rs.getInt(1),user);
    }
    public List<Map<String,Object>> authorizations(int user) {
        return jdbc.queryForList("SELECT d.id AS doctor_id,d.name,d.specialty,COALESCE(a.authorized,0) AS authorized FROM doctor d LEFT JOIN health_doctor_authorization a ON a.doctor_id=d.id AND a.user_id=? WHERE d.status=1 AND EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=? AND c.doctor_id=d.id AND c.status=0) ORDER BY d.id",user,user);
    }
    public void authorize(int user,int doctor,boolean enabled) {
        tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            if(enabled && jdbc.queryForObject("SELECT COUNT(*) FROM doctor d WHERE d.id=? AND d.status=1 AND EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=? AND c.doctor_id=d.id AND c.status=0)",Integer.class,doctor,user)==0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能授权当前有效咨询关联的医生");
            if(jdbc.update("UPDATE health_doctor_authorization SET authorized=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND doctor_id=?",enabled?1:0,user,doctor)==0)
                jdbc.update("INSERT INTO health_doctor_authorization (user_id,doctor_id,authorized) VALUES (?,?,?)",user,doctor,enabled?1:0);
            return null;
        });
    }
    private String scope(int doctor,List<Object> args) {
        args.add(doctor);
        return " EXISTS (SELECT 1 FROM health_doctor_authorization a JOIN doctor d ON d.id=a.doctor_id WHERE a.user_id=e.user_id AND a.doctor_id=? AND a.authorized=1 AND d.status=1 AND EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=e.user_id AND c.doctor_id=a.doctor_id AND c.status=0))";
    }
    public Map<String,Object> list(int doctor,int page,int size,String level,String state,String handling,String from,String to) {
        if(page<1 || page>100000 || size<1 || size>50) throw new IllegalArgumentException("分页范围无效");
        List<Object> args=new ArrayList<>();String where=" WHERE "+scope(doctor,args);
        if(level!=null && !level.isEmpty()) { if(!Arrays.asList("GENERAL","REPEATED","EMERGENCY").contains(level))throw new IllegalArgumentException("等级无效");where+=" AND e.level=?";args.add(level); }
        if(state!=null && !state.isEmpty()) { if(!Arrays.asList("ACTIVE","RECOVERED","CLOSED").contains(state))throw new IllegalArgumentException("异常状态无效");where+=" AND e.status=?";args.add(state); }
        if(handling!=null && !handling.isEmpty()) { if(!Arrays.asList("OPEN","ACKNOWLEDGED","RESOLVED").contains(handling))throw new IllegalArgumentException("处理状态无效");where+=" AND "+HANDLING+"=?";args.add(handling); }
        try {
            if(from!=null && !from.isEmpty()) {where+=" AND e.updated_at>=?";args.add(Timestamp.valueOf(LocalDate.parse(from).atStartOfDay()));}
            if(to!=null && !to.isEmpty()) {where+=" AND e.updated_at<?";args.add(Timestamp.valueOf(LocalDate.parse(to).plusDays(1).atStartOfDay()));}
            if(from!=null && to!=null && !from.isEmpty() && !to.isEmpty() && from.compareTo(to)>0)throw new IllegalArgumentException("开始日期不能晚于结束日期");
        } catch(java.time.format.DateTimeParseException invalid) {throw new IllegalArgumentException("日期格式应为YYYY-MM-DD");}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event e"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.*,u.user_name AS patient_name,"+HANDLING+" AS handling FROM health_alert_event e JOIN `user` u ON u.id=e.user_id"+where+" ORDER BY e.updated_at DESC,e.id DESC LIMIT ? OFFSET ?",args.toArray());
        List<Map<String,Object>> items=new ArrayList<>();rows.forEach(r->items.add(event(r)));
        Map<String,Object> out=new LinkedHashMap<>();out.put("items",items);out.put("total",total);return out;
    }
    private Map<String,Object> event(Map<String,Object> r) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:Arrays.asList("id","user_id","metric_key","level","status","occurrences","patient_name","handling"))out.put(key,r.get(key));
        out.put("opened_at",r.get("opened_at").toString());out.put("updated_at",r.get("updated_at").toString());out.put("details",JSON.parseObject(r.get("details_json").toString()));
        out.put("kind","SOS".equals(r.get("metric_key"))?"SOS":"MEASUREMENT");
        if("SOS".equals(r.get("metric_key")))out.put("sos",jdbc.queryForMap("SELECT status,confirmation_deadline,escalation_stage,acknowledged_at,resolved_at FROM health_sos_event WHERE event_id=?",r.get("id")));
        return out;
    }
    private Map<String,Object> requireEvent(int doctor,long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.*,u.user_name AS patient_name,"+HANDLING+" AS handling FROM health_alert_event e JOIN `user` u ON u.id=e.user_id WHERE e.id=?",id);
        if(rows.isEmpty() || !allowed(((Number)rows.get(0).get("user_id")).intValue(),doctor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看或处理该患者预警");
        return rows.get(0);
    }
    public Map<String,Object> detail(int doctor,long id) {
        Map<String,Object> r=requireEvent(doctor,id),out=event(r);
        out.put("actions",jdbc.queryForList("SELECT a.id,a.doctor_id,d.name AS doctor_name,a.action,a.phase,a.note,a.created_at FROM health_alert_action a JOIN doctor d ON d.id=a.doctor_id WHERE a.event_id=? ORDER BY a.id",id));
        List<Map<String,Object>> samples=new ArrayList<>();
        for(Map<String,Object> evaluation:jdbc.queryForList("SELECT measurement_id,result_json,evaluated_at FROM health_alert_evaluation WHERE user_id=? AND measurement_id>=? AND measurement_id<=? AND status='COMPLETE' ORDER BY measurement_id DESC LIMIT 100",r.get("user_id"),r.get("first_measurement_id"),r.get("last_measurement_id"))) {
            for(Object value:JSON.parseObject(evaluation.get("result_json").toString()).getJSONArray("items")) {
                Map<?,?> item=(Map<?,?>)value;
                if(Objects.equals(item.get("metric"),r.get("metric_key"))) {Map<String,Object> sample=new LinkedHashMap<>();sample.put("measurementId",evaluation.get("measurement_id"));sample.put("item",item);samples.add(sample);}
            }
        }
        out.put("measurements",samples);return out;
    }
    public void action(int doctor,long id,String action,String note) {
        if(!Arrays.asList("ACKNOWLEDGED","RESOLVED").contains(action) || note==null || note.trim().isEmpty() || note.length()>1000)throw new IllegalArgumentException("请填写1至1000字的处理备注");
        tx.execute(s->{
            Map<String,Object> first=requireEvent(doctor,id);int user=((Number)first.get("user_id")).intValue();
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            Map<String,Object> r=requireEvent(doctor,id);
            if("RESOLVED".equals(r.get("handling"))) {
                if("RESOLVED".equals(action))return null;
                throw new ResponseStatusException(HttpStatus.CONFLICT,"事件已处理，不能退回待处理状态");
            }
            if("ACKNOWLEDGED".equals(action) && "ACKNOWLEDGED".equals(r.get("handling"))) return null;
            jdbc.update("INSERT INTO health_alert_action (event_id,doctor_id,action,phase,note) VALUES (?,?,?,?,?)",id,doctor,action,r.get("level"),note.trim());
            if("SOS".equals(r.get("metric_key"))) {
                if("RESOLVED".equals(action)) {
                    jdbc.update("UPDATE health_sos_event SET status='RESOLVED',acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP),resolved_at=CURRENT_TIMESTAMP,notification_error=NULL,planned_stage=escalation_stage WHERE event_id=?",id);
                    jdbc.update("UPDATE health_alert_event SET status='CLOSED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
                    jdbc.update("UPDATE health_notification_task SET status='CANCELLED',error_code='SOS_RESOLVED',retry_at=NULL WHERE event_id=? AND phase LIKE 'SOS_%' AND status IN ('PENDING','FAILED')",id);
                } else jdbc.update("UPDATE health_sos_event SET status='ACKNOWLEDGED',acknowledged_at=CURRENT_TIMESTAMP WHERE event_id=? AND status='OPEN'",id);
                jdbc.update("UPDATE health_notification_task SET status='CANCELLED',error_code='SOS_ALREADY_CONFIRMED',retry_at=NULL WHERE event_id=? AND phase LIKE 'SOS_ESC_%' AND status IN ('PENDING','FAILED')",id);
            }
            return null;
        });
    }
}
