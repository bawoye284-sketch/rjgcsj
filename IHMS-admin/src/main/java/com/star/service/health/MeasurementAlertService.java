package com.star.service.health;

import com.alibaba.fastjson2.JSON;
import com.star.context.LocalThreadHolder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDateTime;
import java.util.*;

/** Stored measurements only. Rules are demonstration configuration, not diagnoses. */
@Service
public class MeasurementAlertService {
    public static final List<String> METRICS = Arrays.asList("systolic_pressure", "diastolic_pressure", "heart_rate", "spo2", "temperature", "respiratory_rate");
    private static final String[] LABELS = {"收缩压", "舒张压", "心率", "血氧", "体温", "呼吸频率"};
    private static final String[] SYMBOLS = {"sys", "dia", "bpm", "spo2", "temp", "rr"};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @Value("${health-alert.retry-enabled:true}") private boolean retryEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private com.star.service.notification.HealthNotificationService notifications;

    public MeasurementAlertService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Called inside the measurement write transaction, before commit. */
    public void enqueue(int user, long measurement) {
        jdbc.update("INSERT INTO health_alert_evaluation (measurement_id,user_id,status) VALUES (?,?,'PENDING')", measurement, user);
    }

    /** Never propagates a detector failure to an already committed measurement. */
    public void processUser(int user) {
        for (int i = 0; i < 50; i++) {
            final long[] current = {0};
            try {
                Boolean completed = tx.execute(s -> {
                    // A real database row lock serializes this patient's jobs across JVMs.
                    jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE", Integer.class, user);
                    List<Map<String,Object>> jobs = jdbc.queryForList("SELECT * FROM health_alert_evaluation WHERE user_id=? AND status<>'COMPLETE' ORDER BY measurement_id LIMIT 1", user);
                    if (jobs.isEmpty()) return false;
                    Map<String,Object> job = jobs.get(0);
                    if (((Number)job.get("attempts")).intValue() >= 3 || (job.get("retry_at") != null && ((Timestamp)job.get("retry_at")).toLocalDateTime().isAfter(LocalDateTime.now()))) return false;
                    current[0] = ((Number)job.get("measurement_id")).longValue();
                    evaluate(user, current[0]);
                    return true;
                });
                if (!Boolean.TRUE.equals(completed)) return;
                notifications.captureSafely(user);
            } catch (RuntimeException failure) {
                if (current[0] != 0) {
                    try {
                        tx.execute(s -> {
                            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE", Integer.class, user);
                            jdbc.update("UPDATE health_alert_evaluation SET status='FAILED',attempts=attempts+1,error_message=?,retry_at=? WHERE measurement_id=? AND status<>'COMPLETE'",
                                    "规则检测失败（" + failure.getClass().getSimpleName() + "），测量已保存，可重试", Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)), current[0]);
                            return null;
                        });
                    } catch (RuntimeException unavailable) { /* Durable PENDING job remains if the database is unavailable. */ }
                }
                return;
            }
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void retryPending() {
        if (!retryEnabled) return;
        try {
            List<Integer> users = jdbc.query("SELECT DISTINCT user_id FROM health_alert_evaluation WHERE status<>'COMPLETE' AND attempts<3 AND (retry_at IS NULL OR retry_at<=CURRENT_TIMESTAMP) LIMIT 50", (r,n)->r.getInt(1));
            users.forEach(this::processUser);
        } catch (RuntimeException unavailable) { /* Retry on the next tick; never interfere with measurement writes. */ }
    }

    private void evaluate(int user, long id) {
        Map<String,Object> m = jdbc.queryForMap("SELECT * FROM elder_vital_measurement WHERE id=? AND user_id=?", id, user);
        LocalDateTime time = ((Timestamp)m.get("measured_at")).toLocalDateTime();
        List<Map<String,Object>> items = new ArrayList<>();
        boolean alert = false, partial = false;
        String overallLevel = "NONE";
        for (Map<String,Object> rule : rules()) {
            String key = rule.get("metricKey").toString();
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("metric", key); item.put("name", rule.get("name")); item.put("value", m.get(key));
            item.put("ruleRevision", rule.get("revision")); item.put("range", rule.get("valueRange"));
            item.put("rule",rule);
            items.add(item);
            if (m.get(key) == null) { item.put("state", "MISSING"); partial = true; continue; }
            List<Long> firstApplicable=jdbc.query("SELECT first_measurement_id FROM health_reference_activation WHERE metric_key=?",(rs,n)->rs.getLong(1),key);
            if(!firstApplicable.isEmpty() && id<firstApplicable.get(0)) {item.put("state","UNCONFIGURED");item.put("range",null);partial=true;continue;}
            if (!Boolean.TRUE.equals(rule.get("enabled"))) { item.put("state", "DISABLED"); partial = true; continue; }
            if (rule.get("valueRange") == null) { item.put("state", "UNCONFIGURED"); partial = true; continue; }
            double[] range = parseRange(rule.get("valueRange").toString());
            double value = ((Number)m.get(key)).doubleValue();
            int count = ((Number)rule.get("consecutiveCount")).intValue(), days = ((Number)rule.get("windowDays")).intValue();
            int baseline = ((Number)rule.get("baselineCount")).intValue();
            // key is from the fixed METRICS whitelist, never a request SQL fragment.
            List<Double> history = jdbc.query("SELECT " + key + " FROM elder_vital_measurement WHERE user_id=? AND id<? AND id>=? AND measured_at>=? AND measured_at<=? AND " + key + " IS NOT NULL ORDER BY id DESC LIMIT ?",
                    (r,n)->r.getDouble(1), user, id, firstApplicable.isEmpty()?0:firstApplicable.get(0), Timestamp.valueOf(time.minusDays(days)), Timestamp.valueOf(time), Math.max(count-1, baseline));
            List<String> reasons = new ArrayList<>();
            boolean outside = value < range[0] || value > range[1];
            if (outside) reasons.add("本次值 " + value + " 超出配置范围 " + rule.get("valueRange"));
            boolean repeated = outside && history.size() >= count-1 && history.subList(0,count-1).stream().allMatch(v->v<range[0] || v>range[1]);
            if (repeated) reasons.add("最近 " + count + " 次有效测量连续超出配置范围（" + days + "天内）");
            boolean changeEnabled = rule.get("changeDelta") != null;
            boolean historyEnough = history.size() >= count-1 && (!changeEnabled || history.size() >= baseline);
            item.put("historyState", historyEnough ? "AVAILABLE" : "INSUFFICIENT");
            item.put("changeState", !changeEnabled ? "DISABLED" : history.size()<baseline ? "INSUFFICIENT" : "AVAILABLE");
            if (!historyEnough) partial = true;
            if (changeEnabled && history.size() >= baseline) {
                double mean = history.subList(0,baseline).stream().mapToDouble(Double::doubleValue).average().orElseThrow(IllegalStateException::new);
                item.put("baseline", mean);
                if (Math.abs(value-mean) >= ((Number)rule.get("changeDelta")).doubleValue()) reasons.add("较此前 " + baseline + " 次有效测量均值变化达到配置幅度 " + rule.get("changeDelta") + "（仅为变化提示）");
            }
            String level = repeated ? "REPEATED" : reasons.isEmpty() ? "NONE" : "GENERAL";
            item.put("state", reasons.isEmpty() ? "WITHIN_RANGE" : "ALERT");
            item.put("level", level); item.put("reasons", reasons);
            updateEvent(user, id, key, level, item, time);
            if (!reasons.isEmpty()) { alert = true; if (!"REPEATED".equals(overallLevel)) overallLevel = level; }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("outcome", alert ? "ALERT" : partial ? "INCOMPLETE" : "WITHIN_RULES");
        result.put("level", overallLevel); result.put("items", items);
        jdbc.update("UPDATE health_alert_evaluation SET status='COMPLETE',attempts=attempts+1,result_json=?,error_message=NULL,retry_at=NULL,evaluated_at=CURRENT_TIMESTAMP WHERE measurement_id=?",
                JSON.toJSONString(result), id);
    }

    private void updateEvent(int user, long measurement, String key, String level, Map<String,Object> item, LocalDateTime time) {
        List<Map<String,Object>> active = jdbc.queryForList("SELECT id,level FROM health_alert_event WHERE user_id=? AND metric_key=? AND status='ACTIVE'", user, key);
        if ("NONE".equals(level)) {
            for (Map<String,Object> e : active) {
                jdbc.update("UPDATE health_alert_event SET status='RECOVERED',last_measurement_id=?,updated_at=?,recovered_at=? WHERE id=?", measurement, Timestamp.valueOf(time), Timestamp.valueOf(time), e.get("id"));
                item.put("recoveredEventId", e.get("id")); item.put("previousLevel", e.get("level"));
            }
            return;
        }
        if (!active.isEmpty()) {
            Map<String,Object> e = active.get(0);
            item.put("eventId", e.get("id")); item.put("previousLevel", e.get("level"));
            jdbc.update("UPDATE health_alert_event SET level=?,last_measurement_id=?,occurrences=occurrences+1,details_json=?,updated_at=? WHERE id=?",
                    level, measurement, JSON.toJSONString(item), Timestamp.valueOf(time), e.get("id"));
        } else {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(c->{
                PreparedStatement p = c.prepareStatement("INSERT INTO health_alert_event (user_id,metric_key,level,status,first_measurement_id,last_measurement_id,details_json,opened_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?,?,?,?)", new String[]{"id"});
                Object[] values = {user,key,level,measurement,measurement,JSON.toJSONString(item),Timestamp.valueOf(time),Timestamp.valueOf(time)};
                for (int i=0;i<values.length;i++) p.setObject(i+1,values[i]); return p;
            }, keys);
            item.put("eventId", keys.getKey().longValue());
        }
    }

    public List<Map<String,Object>> rules() {
        List<Map<String,Object>> out = new ArrayList<>();
        for (int i=0;i<METRICS.size();i++) {
            String key = METRICS.get(i);
            Map<String,Object> r = jdbc.queryForMap("SELECT * FROM health_alert_rule WHERE metric_key=?", key);
            Object config = r.get("config_id");
            List<Map<String,Object>> configs = config == null
                    ? jdbc.queryForList("SELECT id,value_range FROM health_model_config WHERE is_global=1 AND LOWER(symbol)=? ORDER BY id", SYMBOLS[i])
                    : jdbc.queryForList("SELECT id,value_range FROM health_model_config WHERE id=? AND is_global=1", config);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("metricKey",key); row.put("name",LABELS[i]); row.put("configId",config);
            row.put("effectiveConfigId",configs.size()==1 ? configs.get(0).get("id") : null);
            row.put("valueRange",configs.size()==1 ? configs.get(0).get("value_range") : null);
            row.put("enabled",((Number)r.get("enabled")).intValue()==1);
            row.put("consecutiveCount",r.get("consecutive_count")); row.put("windowDays",r.get("window_days"));
            row.put("changeDelta",r.get("change_delta")); row.put("baselineCount",r.get("baseline_count")); row.put("revision",r.get("revision"));
            row.put("severeEnabled",false); out.add(row);
        }
        return out;
    }

    @Data
    public static class RuleUpdate {
        private Integer configId;
        private Boolean enabled;
        private Integer consecutiveCount;
        private Integer windowDays;
        private Double changeDelta;
        private Integer baselineCount;
    }

    public void updateRule(String key, RuleUpdate r) {
        if (!Integer.valueOf(1).equals(LocalThreadHolder.getRoleId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可修改预警规则");
        if (!METRICS.contains(key) || r==null || r.enabled==null || r.consecutiveCount==null || r.consecutiveCount<2 || r.consecutiveCount>20
                || r.windowDays==null || r.windowDays<1 || r.windowDays>90 || r.baselineCount==null || r.baselineCount<2 || r.baselineCount>20
                || (r.changeDelta!=null && (!Double.isFinite(r.changeDelta) || r.changeDelta<=0 || r.changeDelta>10000))) throw new IllegalArgumentException("规则参数无效");
        if (r.configId!=null) {
            List<String> ranges = jdbc.query("SELECT value_range FROM health_model_config WHERE id=? AND is_global=1",(rs,n)->rs.getString(1),r.configId);
            if (ranges.size()!=1) throw new IllegalArgumentException("请选择已存在的全局指标配置");
            parseRange(ranges.get(0));
        }
        jdbc.update("UPDATE health_alert_rule SET config_id=?,enabled=?,consecutive_count=?,window_days=?,change_delta=?,baseline_count=?,revision=revision+1 WHERE metric_key=?",
                r.configId,r.enabled?1:0,r.consecutiveCount,r.windowDays,r.changeDelta,r.baselineCount,key);
    }

    public static double[] parseRange(String text) {
        try {
            String[] parts = text.split(",",-1);
            if (parts.length!=2) throw new IllegalArgumentException();
            double low=Double.parseDouble(parts[0].trim()), high=Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(low) || !Double.isFinite(high) || low>=high) throw new IllegalArgumentException();
            return new double[]{low,high};
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("指标范围应为有效的最小值,最大值"); }
    }

    public Map<String,Object> status(int user, Long measurement) {
        List<Map<String,Object>> rows = measurement==null
                ? jdbc.queryForList("SELECT e.*,m.measured_at FROM health_alert_evaluation e JOIN elder_vital_measurement m ON m.id=e.measurement_id WHERE e.user_id=? ORDER BY e.measurement_id DESC LIMIT 1",user)
                : jdbc.queryForList("SELECT e.*,m.measured_at FROM health_alert_evaluation e JOIN elder_vital_measurement m ON m.id=e.measurement_id WHERE e.user_id=? AND e.measurement_id=?",user,measurement);
        if (rows.isEmpty() && measurement!=null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"测量检测记录不存在");
        if(rows.isEmpty()) return null;
        Map<String,Object> out=evaluation(rows.get(0));
        if("PENDING".equals(out.get("status"))) {
            List<Long> blocked=jdbc.query("SELECT measurement_id FROM health_alert_evaluation WHERE user_id=? AND status='FAILED' AND measurement_id<? ORDER BY measurement_id LIMIT 1",(rs,n)->rs.getLong(1),user,out.get("measurementId"));
            if(!blocked.isEmpty()) out.put("blockingMeasurementId",blocked.get(0));
        }
        return out;
    }

    private Map<String,Object> evaluation(Map<String,Object> r) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("measurementId",r.get("measurement_id")); out.put("status",r.get("status")); out.put("attempts",r.get("attempts"));
        out.put("measuredAt",r.get("measured_at").toString()); out.put("error",r.get("error_message"));
        out.put("result",r.get("result_json")==null?null:JSON.parseObject(r.get("result_json").toString())); return out;
    }

    public Map<String,Object> history(int user,int page,int size) {
        if (page<1 || page>100000 || size<1 || size>50) throw new IllegalArgumentException("分页范围无效");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_alert_event WHERE user_id=? AND metric_key<>'SOS' ORDER BY id DESC LIMIT ? OFFSET ?",user,size,(page-1)*size);
        List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> r:rows) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",r.get("id"));item.put("metric",r.get("metric_key"));item.put("level",r.get("level"));item.put("status",r.get("status"));
            item.put("occurrences",r.get("occurrences"));item.put("openedAt",r.get("opened_at").toString());item.put("updatedAt",r.get("updated_at").toString());
            item.put("details",JSON.parseObject(r.get("details_json").toString()));items.add(item);
        }
        Map<String,Object> out=new LinkedHashMap<>(); out.put("items",items);
        out.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event WHERE user_id=? AND metric_key<>'SOS'",Long.class,user));return out;
    }

    public void retry(int user,long measurement) {
        tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            status(user,measurement);
            jdbc.update("UPDATE health_alert_evaluation SET attempts=0,retry_at=NULL WHERE user_id=? AND measurement_id=? AND status<>'COMPLETE'",user,measurement);
            return null;
        });
        processUser(user);
    }
}
