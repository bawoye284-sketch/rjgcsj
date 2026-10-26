package com.star;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.star.context.LocalThreadHolder;
import com.star.model.dto.risk.*;
import com.star.service.health.MeasurementAlertService;
import com.star.service.risk.PersonalizedRiskService;
import com.star.service.risk.RiskModelClient;
import com.star.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:alert-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "model-runtime.auto-start=false", "health-alert.retry-enabled=false", "health-notification.worker-enabled=false"})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class MeasurementAlertIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalizedRiskService personal;
    @Autowired MeasurementAlertService alerts;
    @Autowired MockMvc mvc;
    @MockBean RiskModelClient model;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM health_alert_evaluation WHERE user_id=2");
        jdbc.update("DELETE FROM health_alert_event WHERE user_id=2");
        jdbc.update("DELETE FROM personalized_risk_assessment WHERE user_id=2");
        jdbc.update("DELETE FROM elder_vital_measurement WHERE user_id=2");
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        jdbc.update("UPDATE health_model_config SET value_range='36.5,37.3' WHERE is_global=1 AND LOWER(symbol)='temp'");
        jdbc.update("UPDATE health_alert_rule SET config_id=NULL,enabled=1,consecutive_count=3,window_days=7,change_delta=NULL,baseline_count=3,revision=1");
        when(model.predict(anyMap())).thenAnswer(invocation->{
            PersonalizedRiskResponse r=new PersonalizedRiskResponse();
            r.setGroupRiskProbability(0.15);r.setPersonalChangeScore(0.0);r.setRiskLevel("关注");
            r.setBaselineAvailable(false);r.setReasons(Collections.singletonList("test model"));r.setSingleAlerts(Collections.emptyList());
            r.setAssessmentBasis("NUMERIC5_AGGREGATE_HISTORY_V1");r.setModelName("test");r.setModelVersion("unchanged");return r;
        });
    }
    @AfterEach void clear() { LocalThreadHolder.clear(); }
    VitalMeasurementDto heart(double value) { VitalMeasurementDto m=new VitalMeasurementDto();m.setHeartRate(value);return m; }
    CurrentRiskAssessmentDto input(String id,double value) {
        CurrentRiskAssessmentDto d=new CurrentRiskAssessmentDto();
        ElderRiskProfileDto p=new ElderRiskProfileDto();p.setAge(72);p.setSexMale(1);p.setBmi(24.5);
        d.setProfile(p);d.setMeasurement(heart(value));d.setRequestId(id);return d;
    }
    JSONObject result(long id) { return JSON.parseObject(JSON.toJSONString(alerts.status(2,id).get("result"))); }
    JSONObject heartResult(long id) { return result(id).getJSONArray("items").getJSONObject(2); }
    int events() { return jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event WHERE user_id=2",Integer.class); }

    @Test void normalMeasurementDoesNotAlertAndMissingFieldsStayUnknown() {
        long id=personal.saveMeasurement(2,heart(78));
        assertEquals(0,events());assertEquals("INCOMPLETE",result(id).getString("outcome"));
        assertEquals("MISSING",result(id).getJSONArray("items").getJSONObject(0).getString("state"));
        assertEquals("INSUFFICIENT",heartResult(id).getString("historyState"));
    }
    @Test void singleAbnormalCreatesEvent() {
        long id=personal.saveMeasurement(2,heart(110));
        assertEquals(1,events());assertEquals("GENERAL",result(id).getString("level"));
        assertTrue(heartResult(id).getJSONArray("reasons").getString(0).contains("60,100"));
    }
    @Test void consecutiveValidReadingsReuseEventAndRecordLevelChange() {
        personal.saveMeasurement(2,heart(110));
        VitalMeasurementDto other=new VitalMeasurementDto();other.setSpo2(98.0);personal.saveMeasurement(2,other);
        personal.saveMeasurement(2,heart(111));long third=personal.saveMeasurement(2,heart(112));
        assertEquals(1,events());assertEquals("REPEATED",result(third).getString("level"));
        assertEquals("GENERAL",heartResult(third).getString("previousLevel"));
        assertEquals(3,jdbc.queryForObject("SELECT occurrences FROM health_alert_event WHERE user_id=2",Integer.class));
        personal.saveMeasurement(2,heart(80));
        assertEquals("RECOVERED",jdbc.queryForObject("SELECT status FROM health_alert_event WHERE user_id=2",String.class));
        personal.saveMeasurement(2,heart(110));assertEquals(2,events());
    }
    @Test void insufficientHistoryDoesNotInventDeterioration() {
        jdbc.update("UPDATE health_alert_rule SET change_delta=10 WHERE metric_key='heart_rate'");
        long id=personal.saveMeasurement(2,heart(95));
        assertEquals(0,events());assertEquals("INSUFFICIENT",heartResult(id).getString("changeState"));
    }
    @Test void recentChangeUsesOnlyPriorValidMeasurements() {
        jdbc.update("UPDATE health_alert_rule SET change_delta=15 WHERE metric_key='heart_rate'");
        personal.saveMeasurement(2,heart(70));personal.saveMeasurement(2,heart(70));personal.saveMeasurement(2,heart(70));
        long id=personal.saveMeasurement(2,heart(90));
        assertEquals("ALERT",result(id).getString("outcome"));assertEquals(70,heartResult(id).getDoubleValue("baseline"));
        assertTrue(heartResult(id).getJSONArray("reasons").getString(0).contains("变化"));
    }
    @Test void oldReadingsOutsideWindowDoNotCountAsConsecutive() {
        personal.saveMeasurement(2,heart(110));personal.saveMeasurement(2,heart(110));
        jdbc.update("UPDATE elder_vital_measurement SET measured_at=? WHERE user_id=2",Timestamp.valueOf(LocalDateTime.now().minusDays(8)));
        long id=personal.saveMeasurement(2,heart(111));assertEquals("GENERAL",result(id).getString("level"));
    }
    @Test void sameRequestReplaysSameMeasurementAndEvaluation() {
        CurrentRiskAssessmentDto d=input(UUID.randomUUID().toString(),110);
        PersonalizedRiskResponse a=personal.assessCurrent(2,d), b=personal.assessCurrent(2,d);
        assertEquals(a.getMeasurementId(),b.getMeasurementId());assertEquals(a.getAssessmentId(),b.getAssessmentId());
        assertEquals(1,events());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE user_id=2",Integer.class));
        verify(model,times(1)).predict(anyMap());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->personal.assessCurrent(2,input(d.getRequestId(),120)));
    }
    @Test void independentMeasurementEndpointTriggersDetection() throws Exception {
        mvc.perform(post("/personalized-risk/measurement").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content("{\"heartRate\":110}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.measurementId").isNumber());
        assertEquals(1,events());
    }
    @Test void assessCurrentEndpointReturnsDetectionLinkedMeasurement() throws Exception {
        mvc.perform(post("/personalized-risk/assess-current").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content(JSON.toJSONString(input(UUID.randomUUID().toString(),110))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.measurementId").isNumber());
        assertEquals(1,events());
    }
    @Test void detectionFailurePreservesMeasurementAndPredictionThenRetries() {
        jdbc.update("UPDATE health_model_config SET value_range='invalid' WHERE id=2");
        PersonalizedRiskResponse r=personal.assessCurrent(2,input(UUID.randomUUID().toString(),110));
        long id=r.getMeasurementId();
        assertEquals("FAILED",alerts.status(2,id).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM personalized_risk_assessment WHERE id=?",Integer.class,r.getAssessmentId()));
        assertEquals(0,events());
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        alerts.retry(2,id);assertEquals("COMPLETE",alerts.status(2,id).get("status"));assertEquals(1,events());
        alerts.retry(2,id);assertEquals(1,events());
    }
    @Test void independentMeasurementAlsoSurvivesDetectionFailure() {
        jdbc.update("UPDATE health_model_config SET value_range='invalid' WHERE id=2");
        long id=personal.saveMeasurement(2,heart(110));
        assertEquals("FAILED",alerts.status(2,id).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE id=?",Integer.class,id));
    }
    @Test void assessExistingHistoryNeverEnqueuesNewDetection() {
        personal.saveMeasurement(2,heart(110));int count=jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE user_id=2",Integer.class);
        personal.assess(2);assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE user_id=2",Integer.class));
        assertEquals(1,events());
    }
    @Test void ordinaryUserCannotChangeOrDeleteGlobalRangesOrRules() throws Exception {
        String token=JwtUtil.toToken(2,2);
        mvc.perform(put("/health-model-config/update").header("token",token).contentType("application/json").content("{\"id\":2,\"valueRange\":\"1,200\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/health-model-config/batchDelete").header("token",token).contentType("application/json").content("[2]"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/health-alerts/rules/heart_rate").header("token",token).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertEquals("60,100",jdbc.queryForObject("SELECT value_range FROM health_model_config WHERE id=2",String.class));
    }
    @Test void adminCanConfigureRulesAndInvalidRangeIsRejected() throws Exception {
        String token=JwtUtil.toToken(1,1);
        mvc.perform(put("/health-alerts/rules/heart_rate").header("token",token).contentType("application/json")
                .content("{\"configId\":2,\"enabled\":true,\"consecutiveCount\":4,\"windowDays\":10,\"baselineCount\":3,\"changeDelta\":20}"))
                .andExpect(status().isOk());
        assertEquals(4,jdbc.queryForObject("SELECT consecutive_count FROM health_alert_rule WHERE metric_key='heart_rate'",Integer.class));
        mvc.perform(put("/health-model-config/update").header("token",token).contentType("application/json").content("{\"id\":2,\"valueRange\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void userCannotReadOrRetryAnotherUsersMeasurementAndDoctorCannotUseUserApi() throws Exception {
        long id=personal.saveMeasurement(2,heart(110));
        mvc.perform(get("/health-alerts/status").param("measurementId",String.valueOf(id)).header("token",JwtUtil.toToken(1001,2))).andExpect(status().isNotFound());
        mvc.perform(post("/health-alerts/"+id+"/retry").header("token",JwtUtil.toToken(1001,2))).andExpect(status().isNotFound());
        mvc.perform(get("/health-alerts").header("token",JwtUtil.toToken(1,3))).andExpect(status().isForbidden());
        mvc.perform(get("/health-alerts").header("token",JwtUtil.toToken(1001,2))).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void concurrentIdenticalRequestsOnlySaveOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        String id=UUID.randomUUID().toString();
        try {
            List<Callable<Long>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->personal.assessCurrent(2,input(id,110)).getMeasurementId());
            Set<Long> ids=new HashSet<>();for(Future<Long> f:pool.invokeAll(calls)) ids.add(f.get(20,TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(1,events());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE user_id=2",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentDifferentMeasurementsReuseOneActiveEvent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> calls=new ArrayList<>();for(int i=0;i<4;i++) calls.add(()->personal.saveMeasurement(2,heart(110)));
            for(Future<Long> f:pool.invokeAll(calls)) assertNotNull(f.get(20,TimeUnit.SECONDS));
            assertEquals(1,events());assertEquals(4,jdbc.queryForObject("SELECT occurrences FROM health_alert_event WHERE user_id=2",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void schemaUpgradeIsRepeatableAndKeepsExistingMeasurements() {
        long id=personal.saveMeasurement(2,heart(78));
        ResourceDatabasePopulator upgrade=new ResourceDatabasePopulator(new ClassPathResource("demo/schema.sql"));
        upgrade.execute(Objects.requireNonNull(jdbc.getDataSource()));upgrade.execute(jdbc.getDataSource());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE id=?",Integer.class,id));
        assertEquals("COMPLETE",alerts.status(2,id).get("status"));
    }
    @Test void unconfiguredTemperatureAndDisabledRulesAreNotNormal() {
        jdbc.update("UPDATE health_model_config SET value_range=NULL WHERE is_global=1 AND LOWER(symbol)='temp'");
        VitalMeasurementDto m=new VitalMeasurementDto();m.setTemperature(36.5);
        long id=personal.saveMeasurement(2,m);assertEquals("UNCONFIGURED",result(id).getJSONArray("items").getJSONObject(4).getString("state"));
        jdbc.update("UPDATE health_alert_rule SET enabled=0 WHERE metric_key='heart_rate'");
        id=personal.saveMeasurement(2,heart(110));assertEquals("DISABLED",heartResult(id).getString("state"));assertEquals(0,events());
    }
    @Test void invalidInputDoesNotSaveMeasurement() throws Exception {
        mvc.perform(post("/personalized-risk/measurement").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content("{\"heartRate\":999}"))
                .andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE user_id=2",Integer.class));
    }
    @Test void sixRealMappingsDecimalReferencesAndRecovery() {
        assertEquals(6,alerts.rules().stream().filter(r->r.get("effectiveConfigId")!=null && r.get("valueRange")!=null).count());
        assertEquals("36.5,37.3",jdbc.queryForObject("SELECT value_range FROM health_model_config WHERE is_global=1 AND symbol='temp'",String.class));
        assertEquals("12,18",jdbc.queryForObject("SELECT value_range FROM health_model_config WHERE is_global=1 AND symbol='rr'",String.class));
        VitalMeasurementDto normal=heart(78);normal.setSystolicPressure(110.0);normal.setDiastolicPressure(70.0);normal.setSpo2(98.0);normal.setTemperature(36.7);normal.setRespiratoryRate(16.0);
        long first=personal.saveMeasurement(2,normal);assertEquals("NONE",result(first).getString("level"));assertEquals(0,events());
        String original=JSON.toJSONString(result(first));
        VitalMeasurementDto abnormal=heart(110);abnormal.setSystolicPressure(150.0);abnormal.setDiastolicPressure(95.0);abnormal.setSpo2(90.0);abnormal.setTemperature(38.0);abnormal.setRespiratoryRate(24.0);
        long second=personal.saveMeasurement(2,abnormal);assertEquals(6,events());
        for(Object raw:result(second).getJSONArray("items"))assertEquals("GENERAL",((JSONObject)raw).getString("level"));
        personal.saveMeasurement(2,abnormal);long third=personal.saveMeasurement(2,abnormal);
        for(Object raw:result(third).getJSONArray("items"))assertEquals("REPEATED",((JSONObject)raw).getString("level"));
        long recovered=personal.saveMeasurement(2,normal);assertEquals("NONE",result(recovered).getString("level"));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event WHERE user_id=2 AND status='RECOVERED'",Integer.class));
        assertEquals(original,JSON.toJSONString(result(first)));assertTrue(alerts.rules().stream().allMatch(r->Boolean.FALSE.equals(r.get("severeEnabled"))));
    }
    @Test void referenceDefaultsDoNotBackfillCompletedOrPreviouslySavedPendingMeasurements() throws Exception {
        jdbc.update("DELETE FROM health_reference_activation WHERE metric_key='temperature'");
        jdbc.update("UPDATE health_model_config SET value_range=NULL WHERE is_global=1 AND LOWER(symbol)='temp'");
        VitalMeasurementDto m=new VitalMeasurementDto();m.setTemperature(38.0);
        long complete=personal.saveMeasurement(2,m);String snapshot=JSON.toJSONString(result(complete));long oldPending=personal.saveMeasurement(2,m);
        jdbc.update("UPDATE health_alert_evaluation SET status='FAILED',attempts=1,result_json=NULL,retry_at=NULL WHERE measurement_id=?",oldPending);
        jdbc.update("DELETE FROM health_notification_checkpoint WHERE measurement_id=?",oldPending);
        String data=org.springframework.util.StreamUtils.copyToString(new ClassPathResource("demo/data.sql").getInputStream(),java.nio.charset.StandardCharsets.UTF_8);
        String defaults=data.substring(data.indexOf("-- Adult resting reference defaults."));
        ResourceDatabasePopulator upgrade=new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(defaults.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        upgrade.execute(jdbc.getDataSource());upgrade.execute(jdbc.getDataSource());
        alerts.retry(2,oldPending);
        assertEquals("UNCONFIGURED",result(oldPending).getJSONArray("items").getJSONObject(4).getString("state"));
        assertEquals(snapshot,JSON.toJSONString(result(complete)));assertEquals(0,events());
        long fresh=personal.saveMeasurement(2,m);assertEquals("GENERAL",result(fresh).getJSONArray("items").getJSONObject(4).getString("level"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp'",Integer.class));
    }
    @Test void failedEarlierJobBlocksLaterJobUntilRetryPreservingOrder() {
        jdbc.update("UPDATE health_model_config SET value_range='invalid' WHERE id=2");
        long first=personal.saveMeasurement(2,heart(110));
        long second=personal.saveMeasurement(2,heart(111));
        assertEquals("PENDING",alerts.status(2,second).get("status"));
        assertEquals(first,((Number)alerts.status(2,second).get("blockingMeasurementId")).longValue());
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        alerts.retry(2,first);
        assertEquals("COMPLETE",alerts.status(2,second).get("status"));
        assertEquals(1,events());assertEquals(2,jdbc.queryForObject("SELECT occurrences FROM health_alert_event WHERE user_id=2",Integer.class));
    }
    @Test void automaticRetryRecoversDurableFailedJob() {
        jdbc.update("UPDATE health_model_config SET value_range='invalid' WHERE id=2");
        long id=personal.saveMeasurement(2,heart(110));
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        jdbc.update("UPDATE health_alert_evaluation SET retry_at=? WHERE measurement_id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),id);
        org.springframework.test.util.ReflectionTestUtils.setField(alerts,"retryEnabled",true);
        try { alerts.retryPending();assertEquals("COMPLETE",alerts.status(2,id).get("status"));assertEquals(1,events()); }
        finally { org.springframework.test.util.ReflectionTestUtils.setField(alerts,"retryEnabled",false); }
    }
    @Test void missingMeasurementNeverRecoversAnActiveMetricEvent() {
        personal.saveMeasurement(2,heart(110));
        VitalMeasurementDto other=new VitalMeasurementDto();other.setSpo2(98.0);personal.saveMeasurement(2,other);
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM health_alert_event WHERE user_id=2",String.class));
    }
    @Test void allConfiguredMeasuredFieldsWithinRulesEventuallyHaveCompleteResult() {
        // Test-only ranges; never seeded into the production/demo configuration.
        jdbc.update("INSERT INTO health_model_config (id,user_id,name,symbol,is_global,value_range) VALUES (90001,1,'test temperature','TEMP',1,'32,43'),(90002,1,'test respiratory','RR',1,'1,80')");
        jdbc.update("UPDATE health_alert_rule SET config_id=90001 WHERE metric_key='temperature'");
        jdbc.update("UPDATE health_alert_rule SET config_id=90002 WHERE metric_key='respiratory_rate'");
        try {
            VitalMeasurementDto m=heart(78);m.setSystolicPressure(120.0);m.setDiastolicPressure(80.0);m.setSpo2(98.0);m.setTemperature(36.5);m.setRespiratoryRate(16.0);
            personal.saveMeasurement(2,m);personal.saveMeasurement(2,m);long id=personal.saveMeasurement(2,m);
            assertEquals("WITHIN_RULES",result(id).getString("outcome"));assertEquals(0,events());
            assertTrue(alerts.rules().stream().noneMatch(r->Boolean.TRUE.equals(r.get("severeEnabled"))));
        } finally { jdbc.update("DELETE FROM health_model_config WHERE id IN (90001,90002)"); }
    }
    @Test void upgradingPreAlertH2DatabasePreservesHistoricalData() throws Exception {
        String schema=org.springframework.util.StreamUtils.copyToString(new ClassPathResource("demo/schema.sql").getInputStream(),java.nio.charset.StandardCharsets.UTF_8);
        String oldSchema=schema.substring(0,schema.indexOf("-- Measurement rule alerts (phase 2)."));
        try(java.sql.Connection connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:pre-alert-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE","sa","")) {
            org.springframework.jdbc.datasource.SingleConnectionDataSource dataSource=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(oldSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new ClassPathResource("demo/data.sql")).execute(dataSource);
            JdbcTemplate old=new JdbcTemplate(dataSource);
            int measurements=old.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class);
            int assessments=old.queryForObject("SELECT COUNT(*) FROM personalized_risk_assessment",Integer.class);
            ResourceDatabasePopulator migration=new ResourceDatabasePopulator(new ClassPathResource("demo/schema.sql"));
            migration.execute(dataSource);migration.execute(dataSource);
            assertEquals(measurements,old.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class));
            assertEquals(assessments,old.queryForObject("SELECT COUNT(*) FROM personalized_risk_assessment",Integer.class));
            assertEquals(6,old.queryForObject("SELECT COUNT(*) FROM health_alert_rule",Integer.class));
            assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation",Integer.class));
        }
    }
}
