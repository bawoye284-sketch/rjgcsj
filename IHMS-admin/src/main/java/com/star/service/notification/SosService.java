package com.star.service.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;

/** User-initiated help only. No model, vital submission or clinical threshold dependency. */
@Service
public class SosService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HealthNotificationService notices;
    private final Environment env;
    @Value("${health-sos.confirmation-seconds:120}") private int confirmationSeconds;
    @Value("${health-sos.escalation-seconds:120}") private int escalationSeconds;
    @Value("${health-sos.max-escalations:2}") private int maxEscalations;
    @Value("${health-sos.initial-contact-count:1}") private int initialContacts;
    @Value("${health-sos.escalation-contact-count:1}") private int escalationContacts;
    @Value("${health-sos.worker-enabled:true}") private boolean workerEnabled;
    @Value("${health-sos.reminder-interval-seconds:60}") private int reminderInterval;
    @Value("${health-sos.reminder-hourly-limit:3}") private int reminderHourlyLimit;
    @Value("${health-sos.reminder-event-limit:20}") private int reminderEventLimit;
    @Value("${health-sos.emergency-number:120}") private String emergencyNumber;
    public SosService(JdbcTemplate jdbc,PlatformTransactionManager manager,HealthNotificationService notices,Environment env) {
        this.jdbc=jdbc;this.notices=notices;this.env=env;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Map<String,Object> policy() {
        Map<String,Object> out=new LinkedHashMap<>();
        boolean demo=Arrays.asList(env.getActiveProfiles()).contains("demo");
        try(Connection c=jdbc.getDataSource().getConnection()) {demo=demo || "H2".equalsIgnoreCase(c.getMetaData().getDatabaseProductName());}
        catch(Exception unavailable) {demo=true;}
        out.put("demo",demo);out.put("emergencyNumber",emergencyNumber.matches("[+0-9]{1,15}")?emergencyNumber:"120");
        out.put("confirmationSeconds",Math.max(10,confirmationSeconds));out.put("escalationSeconds",Math.max(10,escalationSeconds));
        out.put("maxEscalations",Math.max(0,Math.min(5,maxEscalations)));out.put("initialContacts",Math.max(1,Math.min(10,initialContacts)));
        out.put("reminderIntervalSeconds",Math.max(10,reminderInterval));out.put("reminderHourlyLimit",Math.max(1,reminderHourlyLimit));
        return out;
    }
    public long create(int user,String requestId) {
        if(requestId==null || !requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("SOS requestId必须为UUID");
        String key=requestId.toLowerCase(Locale.ROOT);
        long id=tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            List<Long> replay=jdbc.query("SELECT event_id FROM health_sos_request WHERE user_id=? AND request_id=?",(rs,n)->rs.getLong(1),user,key);
            if(!replay.isEmpty())return replay.get(0);
            List<Long> active=jdbc.query("SELECT e.id FROM health_alert_event e JOIN health_sos_event s ON s.event_id=e.id WHERE e.user_id=? AND s.status<>'RESOLVED' ORDER BY e.id LIMIT 1",(rs,n)->rs.getLong(1),user);
            long event;
            if(!active.isEmpty())event=active.get(0);
            else {
                LocalDateTime now=LocalDateTime.now();GeneratedKeyHolder keys=new GeneratedKeyHolder();
                jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO health_alert_event (user_id,metric_key,level,status,first_measurement_id,last_measurement_id,details_json,opened_at,updated_at) VALUES (?,'SOS','EMERGENCY','ACTIVE',NULL,NULL,?,?,?)",new String[]{"id"});
                    p.setInt(1,user);p.setString(2,"{\"name\":\"主动SOS求助\",\"reasons\":[\"用户主动发起紧急求助，不依赖测量或AI评分\"]}");p.setTimestamp(3,Timestamp.valueOf(now));p.setTimestamp(4,Timestamp.valueOf(now));return p;},keys);
                event=keys.getKey().longValue();Timestamp deadline=Timestamp.valueOf(now.plusSeconds(Math.max(10,confirmationSeconds)));
                jdbc.update("INSERT INTO health_sos_event (event_id,confirmation_deadline,next_check_at) VALUES (?,?,?)",event,deadline,deadline);
            }
            jdbc.update("INSERT INTO health_sos_request (user_id,request_id,event_id) VALUES (?,?,?)",user,key,event);
            return event;
        });
        planSafely(id);return id;
    }
    public void planSafely(long id) {
        try {notices.captureSos(id,Math.max(1,Math.min(10,initialContacts)),Math.max(1,Math.min(10,escalationContacts)));}
        catch(RuntimeException unavailable) {
            try {tx.execute(s->{jdbc.update("UPDATE health_sos_event SET notification_error='SOS_CAPTURE_FAILED' WHERE event_id=?",id);return null;});}
            catch(RuntimeException databaseUnavailable) { /* planned_stage remains a durable pending marker. */ }
        }
    }
    public long remind(int user,long event,String requestId) {
        if(requestId==null || !requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("求助提醒请求无效");
        long reminder=tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            List<Map<String,Object>> replay=jdbc.queryForList("SELECT id,event_id FROM health_sos_reminder WHERE user_id=? AND request_id=?",user,requestId.toLowerCase(Locale.ROOT));
            if(!replay.isEmpty()) {
                if(((Number)replay.get(0).get("event_id")).longValue()!=event)throw new ResponseStatusException(HttpStatus.CONFLICT,"该提醒请求已用于其他求助");
                return ((Number)replay.get(0).get("id")).longValue();
            }
            Map<String,Object> state=detail(user,event);
            if("RESOLVED".equals(state.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"该求助已处理，请发起新的求助");
            if(!Boolean.TRUE.equals(state.get("reminderAllowed")))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"提醒过于频繁，请稍后再试");
            int ordinal=jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_reminder WHERE event_id=?",Integer.class,event)+1;
            GeneratedKeyHolder keys=new GeneratedKeyHolder();
            jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO health_sos_reminder (user_id,event_id,request_id,ordinal) VALUES (?,?,?,?)",new String[]{"id"});p.setInt(1,user);p.setLong(2,event);p.setString(3,requestId.toLowerCase(Locale.ROOT));p.setInt(4,ordinal);return p;},keys);
            return keys.getKey().longValue();
        });
        planReminderSafely(reminder);return event;
    }
    private void planReminderSafely(long id) {
        try {notices.captureReminder(id);}
        catch(RuntimeException unavailable) {
            try {tx.execute(s->{jdbc.update("UPDATE health_sos_reminder SET error_code='REMINDER_CAPTURE_FAILED' WHERE id=? AND captured=0",id);return null;});}
            catch(RuntimeException databaseUnavailable) { /* The uncaptured reminder survives restart. */ }
        }
    }
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void tick() {
        if(!workerEnabled)return;
        try {
            for(long reminder:jdbc.query("SELECT id FROM health_sos_reminder WHERE captured=0 ORDER BY id LIMIT 100",(rs,n)->rs.getLong(1)))planReminderSafely(reminder);
            List<Long> ids=jdbc.query("SELECT event_id FROM health_sos_event WHERE status<>'RESOLVED' AND (planned_stage<escalation_stage OR (status='OPEN' AND next_check_at<=CURRENT_TIMESTAMP AND escalation_stage<?)) ORDER BY event_id LIMIT 100",(rs,n)->rs.getLong(1),Math.max(0,Math.min(5,maxEscalations)));
            for(long id:ids) {
                advance(id);planSafely(id);
            }
        } catch(RuntimeException unavailable) { /* Database-backed deadlines survive restart. */ }
    }
    public void advance(long id) {
        tx.execute(s->{
            Integer user=jdbc.queryForObject("SELECT user_id FROM health_alert_event WHERE id=?",Integer.class,id);
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            Map<String,Object> row=jdbc.queryForMap("SELECT * FROM health_sos_event WHERE event_id=?",id);
            int stage=((Number)row.get("escalation_stage")).intValue();
            if("OPEN".equals(row.get("status")) && ((Number)row.get("planned_stage")).intValue()>=stage && stage<Math.max(0,Math.min(5,maxEscalations))
                    && !((Timestamp)row.get("next_check_at")).toLocalDateTime().isAfter(LocalDateTime.now())) {
                jdbc.update("UPDATE health_sos_event SET escalation_stage=escalation_stage+1,next_check_at=? WHERE event_id=?",Timestamp.valueOf(LocalDateTime.now().plusSeconds(Math.max(10,escalationSeconds))),id);
                jdbc.update("UPDATE health_alert_event SET updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
            }
            return null;
        });
    }
    public Map<String,Object> current(int user) {
        List<Long> ids=jdbc.query("SELECT e.id FROM health_alert_event e JOIN health_sos_event s ON s.event_id=e.id WHERE e.user_id=? ORDER BY e.id DESC LIMIT 1",(rs,n)->rs.getLong(1),user);
        return ids.isEmpty()?null:detail(user,ids.get(0));
    }
    public Map<String,Object> businessView(Map<String,Object> raw) {return notices.businessSosView(raw);}
    public Map<String,Object> detail(int user,long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.id,e.opened_at,s.* FROM health_alert_event e JOIN health_sos_event s ON s.event_id=e.id WHERE e.id=? AND e.user_id=?",id,user);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SOS求助事件不存在");
        Map<String,Object> out=new LinkedHashMap<>(rows.get(0));
        LocalDateTime now=LocalDateTime.now(),next=((Timestamp)out.get("opened_at")).toLocalDateTime().plusSeconds(Math.max(10,reminderInterval));
        List<Timestamp> recent=jdbc.query("SELECT created_at FROM health_sos_reminder WHERE user_id=? ORDER BY created_at DESC,id DESC",(rs,n)->rs.getTimestamp(1),user);
        if(!recent.isEmpty() && recent.get(0).toLocalDateTime().plusSeconds(Math.max(10,reminderInterval)).isAfter(next))next=recent.get(0).toLocalDateTime().plusSeconds(Math.max(10,reminderInterval));
        int hourly=Math.max(1,reminderHourlyLimit);
        if(recent.size()>=hourly && recent.get(hourly-1).toLocalDateTime().plusHours(1).isAfter(next))next=recent.get(hourly-1).toLocalDateTime().plusHours(1);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_reminder WHERE event_id=?",Integer.class,id);
        out.put("reminderAllowed",!"RESOLVED".equals(out.get("status")) && count<Math.max(1,Math.min(1000,reminderEventLimit)) && !next.isAfter(now));
        out.put("reminderNextAt",Timestamp.valueOf(next));out.put("reminderLimitReached",count>=Math.max(1,Math.min(1000,reminderEventLimit)));
        out.put("reminderPending",jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_reminder WHERE event_id=? AND captured=0",Integer.class,id));
        out.put("notifications",jdbc.queryForList("SELECT id,recipient_type,recipient_id,channel,phase,priority,status,attempts,error_code,sent_at,read_at FROM health_notification_task WHERE event_id=? ORDER BY id",id));
        out.put("actions",jdbc.queryForList("SELECT a.action,a.note,a.created_at,d.name AS doctor_name FROM health_alert_action a JOIN doctor d ON d.id=a.doctor_id WHERE a.event_id=? ORDER BY a.id",id));
        return out;
    }
    public Map<String,Object> history(int user,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>50)throw new IllegalArgumentException("分页范围无效");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("items",jdbc.queryForList("SELECT e.id,e.opened_at,s.status,s.confirmation_deadline,s.escalation_stage,s.acknowledged_at,s.resolved_at FROM health_alert_event e JOIN health_sos_event s ON s.event_id=e.id WHERE e.user_id=? ORDER BY e.id DESC LIMIT ? OFFSET ?",user,size,(page-1)*size));
        out.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event e JOIN health_sos_event s ON s.event_id=e.id WHERE e.user_id=?",Long.class,user));return out;
    }
}
