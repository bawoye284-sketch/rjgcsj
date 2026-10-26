package com.star.service.notification;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.star.context.LocalThreadHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** Durable capture is separate from detection. Network delivery never shares measurement/event transactions. */
@Service
@DependsOn("healthNotificationSchema")
public class HealthNotificationService {
    private static final Logger log=LoggerFactory.getLogger(HealthNotificationService.class);
    private void diagnostic(String operation,RuntimeException failure) {
        Throwable cause=failure;
        while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
        // Never log exception messages/SQL parameters: they may contain health/contact data.
        if(cause instanceof java.sql.SQLException) {
            java.sql.SQLException sql=(java.sql.SQLException)cause;
            log.warn("Notification {} failed: type={}, sqlState={}, vendorCode={}",operation,cause.getClass().getSimpleName(),sql.getSQLState(),sql.getErrorCode());
        } else log.warn("Notification {} failed: type={}",operation,cause.getClass().getSimpleName());
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate completionTx;
    private final DoctorAlertService doctors;
    private final HealthMailTransport mail;
    @Value("${health-notification.worker-enabled:true}") private boolean workerEnabled;
    @Value("${health-notification.notify-upgrades:true}") private boolean notifyUpgrades;
    @Value("${health-notification.reference-only-metrics:temperature,respiratory_rate}") private String referenceOnlyMetrics;
    public HealthNotificationService(JdbcTemplate jdbc,PlatformTransactionManager manager,DoctorAlertService doctors,HealthMailTransport mail) {
        this.jdbc=jdbc;this.doctors=doctors;this.mail=mail;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionTx=new TransactionTemplate(manager);
    }
    public void captureSafely(int user) {
        try { capture(user); }
        catch(RuntimeException unavailable) {
            diagnostic("capture",unavailable);
            // Record a safe planning failure when the DB is still reachable. If it is not,
            // the completed evaluation itself remains the durable, unconsumed source.
            try {tx.execute(s->{
                jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
                for(Long id:jdbc.query("SELECT e.measurement_id FROM health_alert_evaluation e WHERE e.user_id=? AND e.status='COMPLETE' AND NOT EXISTS (SELECT 1 FROM health_notification_checkpoint c WHERE c.measurement_id=e.measurement_id AND c.status='CAPTURED') ORDER BY e.measurement_id LIMIT 100",(rs,n)->rs.getLong(1),user)) {
                    if(jdbc.update("UPDATE health_notification_checkpoint SET status='FAILED',error_code='CAPTURE_FAILED',attempts=attempts+1 WHERE measurement_id=?",id)==0)
                        jdbc.update("INSERT INTO health_notification_checkpoint (measurement_id,status,error_code,attempts) VALUES (?,'FAILED','CAPTURE_FAILED',1)",id);
                }
                return null;
            });}catch(RuntimeException databaseUnavailable) { diagnostic("checkpoint",databaseUnavailable); }
        }
    }
    public void capture(int user) {
        tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            List<Map<String,Object>> pending=jdbc.queryForList("SELECT e.measurement_id,e.result_json FROM health_alert_evaluation e WHERE e.user_id=? AND e.status='COMPLETE' AND NOT EXISTS (SELECT 1 FROM health_notification_checkpoint c WHERE c.measurement_id=e.measurement_id AND c.status='CAPTURED') ORDER BY e.measurement_id LIMIT 100",user);
            for(Map<String,Object> row:pending) {
                JSONArray items=JSON.parseObject(row.get("result_json").toString()).getJSONArray("items");
                for(int i=0;i<items.size();i++) {
                    JSONObject item=items.getJSONObject(i);Long event=item.getLong("eventId");String level=item.getString("level");
                    if(event==null || !Arrays.asList("GENERAL","REPEATED").contains(level))continue;
                    List<Integer> exists=jdbc.query("SELECT user_id FROM health_alert_event WHERE id=? AND user_id=?",(rs,n)->rs.getInt(1),event,user);
                    if(exists.isEmpty())continue;
                    // Notify once at creation and once on upgrade, never on every abnormal measurement.
                    boolean initial=item.get("previousLevel")==null;
                    if(!initial && (!notifyUpgrades || !"REPEATED".equals(level) || "REPEATED".equals(item.getString("previousLevel"))))continue;
                    long measurement=((Number)row.get("measurement_id")).longValue();
                    task(user,event,measurement,"USER",user,null,"IN_APP",level);
                    if("GENERAL".equals(level) && Arrays.stream(referenceOnlyMetrics.split(",")).map(String::trim).anyMatch(metric->metric.equals(item.getString("metric"))))continue;
                    for(int doctor:doctors.recipients(user))task(user,event,measurement,"DOCTOR",doctor,null,"IN_APP",level);
                    // Family email starts only at the engine's consecutive-abnormal stage.
                    // Single excursions and change-only GENERAL alerts remain in-app.
                    if(!"REPEATED".equals(level))continue;
                    for(Map<String,Object> contact:jdbc.queryForList("SELECT id,revision FROM health_emergency_contact WHERE user_id=? AND authorized=1",user))
                        if(mail.permitsContact(user,((Number)contact.get("id")).longValue()))
                        task(user,event,measurement,"CONTACT",((Number)contact.get("id")).longValue(),((Number)contact.get("revision")).intValue(),"EMAIL",level);
                }
                if(jdbc.update("UPDATE health_notification_checkpoint SET status='CAPTURED',error_code=NULL,captured_at=CURRENT_TIMESTAMP WHERE measurement_id=?",row.get("measurement_id"))==0)
                    jdbc.update("INSERT INTO health_notification_checkpoint (measurement_id) VALUES (?)",row.get("measurement_id"));
            }
            return null;
        });
    }
    private void task(int user,long event,long measurement,String type,long recipient,Integer revision,String channel,String phase) {
        task(user,event,measurement,type,recipient,revision,channel,phase,0);
    }
    private void task(int user,long event,Long measurement,String type,long recipient,Integer revision,String channel,String phase,int priority) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND recipient_type=? AND recipient_id=? AND channel=? AND phase=?",Integer.class,event,type,recipient,channel,phase);
        if(count==0)jdbc.update("INSERT INTO health_notification_task (user_id,event_id,measurement_id,recipient_type,recipient_id,recipient_revision,channel,phase,priority) VALUES (?,?,?,?,?,?,?,?,?)",user,event,measurement,type,recipient,revision,channel,phase,priority);
    }
    public void captureSos(long event,int initialCount,int escalationCount) {
        tx.execute(s->{
            int user=jdbc.queryForObject("SELECT user_id FROM health_alert_event WHERE id=? AND metric_key='SOS'",Integer.class,event);
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            Map<String,Object> row=jdbc.queryForMap("SELECT * FROM health_sos_event WHERE event_id=?",event);
            int stage=((Number)row.get("escalation_stage")).intValue();
            if(((Number)row.get("planned_stage")).intValue()>=stage)return null;
            if("RESOLVED".equals(row.get("status")) || (stage>0 && !"OPEN".equals(row.get("status")))) {
                jdbc.update("UPDATE health_sos_event SET planned_stage=?,notification_error=NULL WHERE event_id=?",stage,event);return null;
            }
            String phase=stage==0?"SOS_INITIAL":"SOS_ESC_"+stage;int priority=100+stage;
            task(user,event,null,"USER",user,null,"IN_APP",phase,priority);
            for(int doctor:doctors.recipients(user))task(user,event,null,"DOCTOR",doctor,null,"IN_APP",phase,priority);
            String verification=mail.mockMode()?"email_verification IN ('DEMO','REAL')":"email_verification='REAL'";
            int limit=stage==0?initialCount:escalationCount;
            List<Map<String,Object>> contacts=jdbc.queryForList("SELECT c.id,c.revision FROM health_emergency_contact c WHERE c.user_id=? AND c.authorized=1 AND "+verification+" AND NOT EXISTS (SELECT 1 FROM health_notification_task t WHERE t.event_id=? AND t.recipient_type='CONTACT' AND t.recipient_id=c.id) ORDER BY c.id LIMIT ?",user,event,mail.localTestMode()?10:limit);
            contacts=contacts.stream().filter(c->mail.permitsContact(user,((Number)c.get("id")).longValue())).limit(limit).collect(java.util.stream.Collectors.toList());
            for(Map<String,Object> c:contacts)if(mail.permitsContact(user,((Number)c.get("id")).longValue()))task(user,event,null,"CONTACT",((Number)c.get("id")).longValue(),((Number)c.get("revision")).intValue(),"EMAIL",phase,priority);
            jdbc.update("UPDATE health_sos_event SET planned_stage=?,notification_error=NULL WHERE event_id=?",stage,event);return null;
        });
    }
    public void captureReminder(long reminder) {
        tx.execute(s->{
            Map<String,Object> source=jdbc.queryForMap("SELECT * FROM health_sos_reminder WHERE id=?",reminder);
            int user=((Number)source.get("user_id")).intValue();long event=((Number)source.get("event_id")).longValue();
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            source=jdbc.queryForMap("SELECT * FROM health_sos_reminder WHERE id=? FOR UPDATE",reminder);
            if(((Number)source.get("captured")).intValue()==1)return null;
            if(!"RESOLVED".equals(jdbc.queryForObject("SELECT status FROM health_sos_event WHERE event_id=?",String.class,event))) {
                String phase="SOS_R_"+source.get("ordinal");
                task(user,event,null,"USER",user,null,"IN_APP",phase,110);
                for(int doctor:doctors.recipients(user))task(user,event,null,"DOCTOR",doctor,null,"IN_APP",phase,110);
                String verification=mail.mockMode()?"email_verification IN ('DEMO','REAL')":"email_verification='REAL'";
                for(Map<String,Object> contact:jdbc.queryForList("SELECT id,revision FROM health_emergency_contact WHERE user_id=? AND authorized=1 AND "+verification,user))
                    if(mail.permitsContact(user,((Number)contact.get("id")).longValue()))
                    task(user,event,null,"CONTACT",((Number)contact.get("id")).longValue(),((Number)contact.get("revision")).intValue(),"EMAIL",phase,110);
            }
            jdbc.update("UPDATE health_sos_reminder SET captured=1,error_code=NULL WHERE id=?",reminder);return null;
        });
    }
    @Scheduled(fixedDelay=15000,initialDelay=15000)
    public void tick() {
        if(!workerEnabled)return;
        try {
            List<Integer> users=jdbc.query("SELECT DISTINCT e.user_id FROM health_alert_evaluation e WHERE e.status='COMPLETE' AND NOT EXISTS (SELECT 1 FROM health_notification_checkpoint c WHERE c.measurement_id=e.measurement_id AND c.status='CAPTURED') LIMIT 50",(rs,n)->rs.getInt(1));
            users.forEach(this::captureSafely);deliverPending();
        } catch(RuntimeException unavailable) { diagnostic("worker",unavailable); }
    }
    public void deliverPending() {
        // An expired send lease may already have reached SMTP. Do not auto-resend it.
        List<Long> expired=jdbc.query("SELECT id FROM health_notification_task WHERE status='SENDING' AND lease_until<CURRENT_TIMESTAMP LIMIT 100",(rs,n)->rs.getLong(1));
        for(long id:expired)tx.execute(s->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_notification_task WHERE id=? FOR UPDATE",id);
            if(!rows.isEmpty()) {
                Map<String,Object> r=rows.get(0);
                if("SENDING".equals(r.get("status")) && r.get("lease_until")!=null && ((Timestamp)r.get("lease_until")).toLocalDateTime().isBefore(LocalDateTime.now()))finish(r,"UNKNOWN","WORKER_INTERRUPTED");
            }
            return null;
        });
        // Filter before LIMIT so an old outbox cannot starve this session's new mail.
        String scope=mail.localTestMode()?" AND (channel<>'EMAIL' OR (id>? AND event_id>? AND (measurement_id IS NULL OR measurement_id>?) AND user_id=?))":"";
        List<Long> ids=jdbc.query("SELECT id FROM health_notification_task WHERE (status='PENDING' OR status='FAILED') AND attempts<3 AND (retry_at IS NULL OR retry_at<=CURRENT_TIMESTAMP)"+scope+" ORDER BY priority DESC,id LIMIT 100",(rs,n)->rs.getLong(1),mail.localTestMode()?mail.localOutboxScope():new Object[0]);
        ids.forEach(this::deliver);
    }
    public void deliver(long id) {
        Map<String,Object> task=tx.execute(s->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_notification_task WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty())return null;
            Map<String,Object> r=rows.get(0);String status=r.get("status").toString();int attempts=((Number)r.get("attempts")).intValue();
            // Keep historical task records intact; opt-in local tests never drain an old outbox.
            if(!mail.permitsTask(r))return null;
            if(!Arrays.asList("PENDING","FAILED").contains(status) || attempts>=3 || (r.get("retry_at")!=null && ((Timestamp)r.get("retry_at")).toLocalDateTime().isAfter(LocalDateTime.now())))return null;
            jdbc.update("UPDATE health_notification_task SET status='SENDING',attempts=attempts+1,lease_until=?,error_code=NULL WHERE id=?",Timestamp.valueOf(LocalDateTime.now().plusMinutes(2)),id);
            jdbc.update("INSERT INTO health_notification_attempt (task_id,attempt_no,status) VALUES (?,?,'SENDING')",id,attempts+1);
            r.put("attempts",attempts+1);return r;
        });
        if(task==null)return;
        try {
            if(task.get("phase").toString().startsWith("SOS_R_") && jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_event WHERE event_id=? AND status<>'RESOLVED'",Integer.class,task.get("event_id"))==0) {
                finish(task,"CANCELLED","SOS_ALREADY_CONFIRMED");return;
            }
            if(task.get("phase").toString().startsWith("SOS_ESC_") && jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_event WHERE event_id=? AND status='OPEN'",Integer.class,task.get("event_id"))==0) {
                finish(task,"CANCELLED","SOS_ALREADY_CONFIRMED");return;
            }
            int user=((Number)task.get("user_id")).intValue();long recipient=((Number)task.get("recipient_id")).longValue();String type=task.get("recipient_type").toString();
            if("CONTACT".equals(type)) {
                deliverContact(task,user,recipient);
            } else if("DOCTOR".equals(type)) {
                if(!doctors.allowed(user,(int)recipient)) {finish(task,"CANCELLED","DOCTOR_ACCESS_REVOKED");return;}
                finish(task,"SENT",null);
            } else if("USER".equals(type) && recipient==user) finish(task,"SENT",null);
            else finish(task,"CANCELLED","INVALID_RECIPIENT_TYPE");
        } catch(HealthMailTransport.DeliveryFailure failure) { finish(task,failure.uncertain?"UNKNOWN":"FAILED",failure.code); }
        catch(RuntimeException unknown) { finish(task,"UNKNOWN","DELIVERY_RESULT_UNKNOWN"); }
    }
    private void deliverContact(Map<String,Object> task,int user,long recipient) {
        // Only the contact row is held while sending. This is a NOTIFICATION transaction;
        // no measurement/event transaction or patient row is held over network I/O.
        // A concurrent revocation/edit is serialized with the final eligibility check.
        tx.execute(s->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_emergency_contact WHERE user_id=? AND id=? FOR UPDATE",user,recipient);
            if(rows.isEmpty() || ((Number)rows.get(0).get("authorized")).intValue()!=1 || !Objects.equals(rows.get(0).get("revision"),task.get("recipient_revision"))) {
                finish(task,"CANCELLED","CONTACT_CHANGED_OR_REVOKED");return null;
            }
            Map<String,Object> contact=rows.get(0);boolean mock=mail.mockMode();String verification=contact.get("email_verification").toString();
            if(!(mock?Arrays.asList("DEMO","REAL").contains(verification):"REAL".equals(verification))) {
                finish(task,"SKIPPED","EMAIL_NOT_VERIFIED_FOR_MODE");return null;
            }
            // Never invoke the SMTP adapter on a decision made under Mock policy.
            // This remains safe even if environment/database availability changes between checks.
            boolean sos=task.get("phase").toString().startsWith("SOS_");
            if(!mock)mail.send(contact.get("email").toString(),sos?"SOS主动紧急求助提醒":"健康测量提醒",sos?"您授权接收提醒的本人主动发出了SOS紧急求助。请尽快联系本人，并在需要时主动联系当地急救。请登录系统查看有权限的信息。电子通知不能替代实际急救响应。":"您授权接收的健康提醒有更新，请及时联系本人并建议其登录系统查看测量规则预警。电子提醒不能代替医疗诊断或急救响应。");
            finish(task,mock?"MOCK_SENT":"SENT",null);return null;
        });
    }
    private void finish(Map<String,Object> task,String status,String error) {
        completionTx.execute(s->{
            Object id=task.get("id"),attempt=task.get("attempts");
            jdbc.update("UPDATE health_notification_task SET status=?,error_code=?,retry_at=?,lease_until=NULL,sent_at=? WHERE id=? AND status='SENDING' AND attempts=?",status,error,
                    "FAILED".equals(status)?Timestamp.valueOf(LocalDateTime.now().plusMinutes(((Number)attempt).longValue())):null,
                    Arrays.asList("SENT","MOCK_SENT").contains(status)?Timestamp.valueOf(LocalDateTime.now()):null,id,attempt);
            jdbc.update("UPDATE health_notification_attempt SET status=?,error_code=?,finished_at=CURRENT_TIMESTAMP WHERE task_id=? AND attempt_no=? AND status='SENDING'",status,error,id,attempt);return null;
        });
    }
    private String ownScope(int role,int id,List<Object> args) {
        if(role==2) {args.add(id);return "t.user_id=?";}
        if(role==3) {args.add(id);args.add(id);return "t.recipient_type='DOCTOR' AND t.recipient_id=? AND EXISTS (SELECT 1 FROM health_doctor_authorization a JOIN doctor d ON d.id=a.doctor_id WHERE a.user_id=t.user_id AND a.doctor_id=? AND a.authorized=1 AND d.status=1 AND (a.bound=1 OR EXISTS (SELECT 1 FROM doctor_consultation c WHERE c.patient_id=t.user_id AND c.doctor_id=a.doctor_id AND c.status=0)))";}
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请使用本人用户或医生账号");
    }
    public Map<String,Object> list(int role,int id,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>50)throw new IllegalArgumentException("分页范围无效");
        List<Object> args=new ArrayList<>();String where=" WHERE "+ownScope(role,id,args)+" AND t.status<>'MOCK_SENT'";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task t"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT t.* FROM health_notification_task t"+where+" ORDER BY t.id DESC LIMIT ? OFFSET ?",args.toArray());
        for(Map<String,Object> r:rows)r.put("attempt_log",jdbc.queryForList("SELECT attempt_no,status,error_code,started_at,finished_at FROM health_notification_attempt WHERE task_id=? ORDER BY attempt_no",r.get("id")));
        Map<String,Object> out=new LinkedHashMap<>();out.put("items",rows);out.put("total",total);out.put("mode",mail.mockMode()?"MOCK":"SMTP");
        out.put("emailServiceEnabled",mail.enabledForDelivery());
        out.put("capturePending",role==2?jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation e WHERE e.user_id=? AND e.status='COMPLETE' AND NOT EXISTS (SELECT 1 FROM health_notification_checkpoint c WHERE c.measurement_id=e.measurement_id AND c.status='CAPTURED')",Integer.class,id):0);
        out.put("captureErrors",role==2?jdbc.queryForList("SELECT c.measurement_id,c.status,c.error_code,c.attempts FROM health_notification_checkpoint c JOIN health_alert_evaluation e ON e.measurement_id=c.measurement_id WHERE e.user_id=? AND c.status='FAILED' ORDER BY c.measurement_id DESC LIMIT 10",id):Collections.emptyList());return out;
    }
    /** User-facing projection only; raw tasks and test diagnostics remain persisted. */
    public Map<String,Object> businessSosView(Map<String,Object> raw) {
        if(raw==null)return null;
        Map<String,Object> out=new LinkedHashMap<>(raw);
        List<Map<String,Object>> visible=new ArrayList<>();
        for(Object value:(List<?>)raw.get("notifications")) {
            @SuppressWarnings("unchecked") Map<String,Object> row=(Map<String,Object>)value;
            if(!"MOCK_SENT".equals(row.get("status")))visible.add(row);
        }
        out.put("notifications",visible);out.put("emailServiceEnabled",mail.enabledForDelivery());
        return out;
    }
    public void retry(int role,int actor,long id) {
        if(role!=2)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅本人可重试家庭通知任务");
        tx.execute(s->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_notification_task WHERE id=? AND user_id=? FOR UPDATE",id,actor);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"通知任务不存在");Map<String,Object> r=rows.get(0);
            if(!"FAILED".equals(r.get("status")) || ((Number)r.get("attempts")).intValue()>=3)throw new ResponseStatusException(HttpStatus.CONFLICT,"仅可重试未达3次上限的明确失败任务；结果不确定不能重发");
            jdbc.update("UPDATE health_notification_task SET retry_at=NULL WHERE id=?",id);return null;
        });deliver(id);
    }
    public void read(int role,int actor,long id) {
        List<Object> args=new ArrayList<>();String scope=ownScope(role,actor,args);args.add(id);
        List<Long> ids=jdbc.query("SELECT t.id FROM health_notification_task t WHERE "+scope+" AND t.channel='IN_APP' AND t.recipient_type=? AND t.recipient_id=? AND t.id=?",(rs,n)->rs.getLong(1),readArgs(role,actor,id));
        if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"站内通知不存在");jdbc.update("UPDATE health_notification_task SET read_at=CURRENT_TIMESTAMP WHERE id=?",id);
    }
    private Object[] readArgs(int role,int actor,long id) {
        List<Object> args=new ArrayList<>();ownScope(role,actor,args);args.add(role==3?"DOCTOR":"USER");args.add(actor);args.add(id);return args.toArray();
    }
}
