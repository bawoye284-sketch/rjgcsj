package com.star;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.star.context.LocalThreadHolder;
import com.star.model.dto.risk.*;
import com.star.service.health.MeasurementAlertService;
import com.star.service.notification.*;
import com.star.service.risk.*;
import com.star.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:notice-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "model-runtime.auto-start=false","health-alert.retry-enabled=false","health-notification.worker-enabled=false",
        "health-notification.mode=SMTP","health-notification.smtp.enabled=true"})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class HealthNotificationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalizedRiskService personal;
    @Autowired MeasurementAlertService alerts;
    @Autowired EmergencyContactService contacts;
    @Autowired DoctorAlertService doctors;
    @Autowired MockMvc mvc;
    @SpyBean HealthMailTransport mail;
    @SpyBean HealthNotificationService notices;
    @MockBean RiskModelClient model;
    @BeforeEach void setup() {
        reset(mail,notices);
        for(String table:Arrays.asList("health_notification_attempt","health_notification_task","health_notification_checkpoint","health_contact_verification","health_emergency_contact","health_doctor_authorization","health_alert_action","health_alert_evaluation","health_alert_event"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM elder_vital_measurement WHERE user_id=2");jdbc.update("DELETE FROM personalized_risk_assessment WHERE user_id=2");
        jdbc.update("UPDATE doctor SET status=1 WHERE id IN (1,2)");jdbc.update("UPDATE doctor_consultation SET status=0 WHERE id IN (1,1001,1002,1003)");
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        jdbc.update("UPDATE health_alert_rule SET config_id=NULL,enabled=1,consecutive_count=3,window_days=7,change_delta=NULL,baseline_count=3");
        when(model.predict(anyMap())).thenAnswer(i->{PersonalizedRiskResponse r=new PersonalizedRiskResponse();r.setGroupRiskProbability(.2);r.setPersonalChangeScore(.0);r.setRiskLevel("关注");r.setReasons(Collections.emptyList());r.setSingleAlerts(Collections.emptyList());r.setModelName("test");r.setModelVersion("test");r.setAssessmentBasis("NUMERIC5_AGGREGATE_HISTORY_V1");return r;});
    }
    @AfterEach void clear() {LocalThreadHolder.clear();}
    EmergencyContactService.Input contactInput(boolean authorized) {
        EmergencyContactService.Input r=new EmergencyContactService.Input();r.name="测试联系人";r.relationship="家属";r.email="relative@example.invalid";r.phone="13800000000";r.authorized=authorized;return r;
    }
    long contact(boolean authorized,boolean verified) {
        long id=contacts.save(2,null,contactInput(authorized));
        if(verified) {Map<String,Object> challenge=contacts.challenge(2,id);assertTrue(contacts.verify(2,id,challenge.get("demoToken").toString()));}
        return id;
    }
    long measurement(double value) {VitalMeasurementDto m=new VitalMeasurementDto();m.setHeartRate(value);return personal.saveMeasurement(2,m);}
    long repeatedMeasurement(double value) {measurement(value);measurement(value);return measurement(value);}
    long event() {return jdbc.queryForObject("SELECT id FROM health_alert_event WHERE user_id=2 ORDER BY id DESC LIMIT 1",Long.class);}
    long emailTask() {return jdbc.queryForObject("SELECT id FROM health_notification_task WHERE recipient_type='CONTACT' ORDER BY id DESC LIMIT 1",Long.class);}
    int tasks() {return jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task",Integer.class);}
    String taskStatus(long id) {return jdbc.queryForObject("SELECT status FROM health_notification_task WHERE id=?",String.class,id);}
    CurrentRiskAssessmentDto assessment() {CurrentRiskAssessmentDto d=new CurrentRiskAssessmentDto();ElderRiskProfileDto p=new ElderRiskProfileDto();p.setAge(72);p.setSexMale(1);p.setBmi(24.5);d.setProfile(p);VitalMeasurementDto m=new VitalMeasurementDto();m.setHeartRate(110.0);d.setMeasurement(m);d.setRequestId(UUID.randomUUID().toString());return d;}

    @Test void normalDoesNotCreateNotificationTasks() {contact(true,true);doctors.authorize(2,1,true);measurement(78);assertEquals(0,tasks());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_checkpoint",Integer.class));}
    @Test void referenceOnlyMetricsKeepOrdinaryAlertsLocalAndNotifyOnRepeatedStage() {
        contact(true,true);doctors.authorize(2,1,true);
        VitalMeasurementDto m=new VitalMeasurementDto();m.setTemperature(38.0);m.setRespiratoryRate(24.0);
        personal.saveMeasurement(2,m);assertEquals(2,tasks());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type<>'USER'",Integer.class));
        personal.saveMeasurement(2,m);assertEquals(2,tasks());personal.saveMeasurement(2,m);assertEquals(8,tasks());
        notices.deliverPending();assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='CONTACT' AND status='MOCK_SENT'",Integer.class));
    }
    @Test void missingPriorityReproducesCaptureFailureAndSixResultsRecoverWithoutDuplicates() {
        contact(true,true);doctors.authorize(2,1,true);
        jdbc.execute("ALTER TABLE health_notification_task DROP COLUMN priority");
        try {
            for(int i=0;i<6;i++)measurement(110+i);
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_evaluation WHERE status='COMPLETE'",Integer.class));
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_checkpoint WHERE status='FAILED' AND error_code='CAPTURE_FAILED'",Integer.class));
            assertEquals(0,tasks());
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE user_id=2",Integer.class));
        } finally {
            new com.star.config.HealthNotificationSchema(jdbc).afterPropertiesSet();
        }
        new com.star.config.HealthNotificationSchema(jdbc).afterPropertiesSet();
        notices.capture(2);notices.capture(2);notices.deliverPending();notices.deliverPending();
        assertEquals(5,tasks()); // Two in-app stages, family email only on repeated stage.
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_checkpoint WHERE status='CAPTURED' AND error_code IS NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='CONTACT' AND status='MOCK_SENT'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='DOCTOR' AND status='SENT'",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_attempt",Integer.class));
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void abnormalCreatesDurableTypedTasksAndMockDelivery() {
        contact(true,true);doctors.authorize(2,1,true);repeatedMeasurement(110);assertEquals(5,tasks());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='USER' AND channel='IN_APP'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='DOCTOR' AND channel='IN_APP'",Integer.class));
        notices.deliverPending();assertEquals("MOCK_SENT",taskStatus(emailTask()));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_attempt",Integer.class));
    }
    @Test void continuousAbnormalityDoesNotDuplicateAndUpgradeNotifiesOnce() {
        contact(true,true);doctors.authorize(2,1,true);measurement(110);measurement(111);assertEquals(2,tasks());
        measurement(112);assertEquals(5,tasks());measurement(113);notices.capture(2);assertEquals(5,tasks());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE phase='REPEATED'",Integer.class));
    }
    @Test void upgradeNotificationPolicyCanDisableUpgrade() {
        org.springframework.test.util.ReflectionTestUtils.setField(notices,"notifyUpgrades",false);
        try {contact(true,true);measurement(110);measurement(111);measurement(112);assertEquals(1,tasks());}
        finally {org.springframework.test.util.ReflectionTestUtils.setField(notices,"notifyUpgrades",true);}
    }
    @Test void sameRequestDoesNotCreateDuplicateTasks() {
        contact(true,true);measurement(110);measurement(111);CurrentRiskAssessmentDto d=assessment();PersonalizedRiskResponse a=personal.assessCurrent(2,d),b=personal.assessCurrent(2,d);
        assertEquals(a.getMeasurementId(),b.getMeasurementId());assertEquals(3,tasks());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE channel='EMAIL'",Integer.class));
    }
    @Test void frontEndCannotSetVerificationOrOtherOwner() throws Exception {
        String response=mvc.perform(post("/emergency-contacts").header("token",JwtUtil.toToken(2,2)).contentType("application/json")
                .content("{\"userId\":1001,\"name\":\"test\",\"relationship\":\"family\",\"email\":\"contact@example.invalid\",\"phone\":\"\",\"authorized\":true,\"email_verification\":\"REAL\",\"verified\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=JSON.parseObject(response).getJSONObject("data").getLongValue("id");
        assertEquals("NONE",contacts.contact(2,id).get("email_verification"));assertEquals(0,contacts.list(1001).size());
        mvc.perform(post("/emergency-contacts/"+id+"/verification").header("token",JwtUtil.toToken(1001,2))).andExpect(status().isNotFound());
        mvc.perform(get("/emergency-contacts").header("token",JwtUtil.toToken(2,3))).andExpect(status().isForbidden());
    }
    @Test void demoVerificationIsOneTimeAndNeverReal() {
        long id=contact(true,false);Map<String,Object> challenge=contacts.challenge(2,id);String token=challenge.get("demoToken").toString();
        assertTrue(contacts.verify(2,id,token));assertFalse(contacts.verify(2,id,token));assertEquals("DEMO",contacts.contact(2,id).get("email_verification"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_contact_verification",Integer.class));
    }
    @Test void failedTokensAreLimitedAndStoredOnlyAsHash() {
        long id=contact(true,false);String token=contacts.challenge(2,id).get("demoToken").toString();
        String hash=jdbc.queryForObject("SELECT token_hash FROM health_contact_verification WHERE contact_id=?",String.class,id);assertEquals(64,hash.length());assertNotEquals(token,hash);
        String wrong="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";for(int i=0;i<5;i++)assertFalse(contacts.verify(2,id,wrong));assertFalse(contacts.verify(2,id,token));
    }
    @Test void changedContactResetsVerificationAndCancelsQueuedTask() {
        long id=contact(true,true);repeatedMeasurement(110);long task=emailTask();
        EmergencyContactService.Input r=contactInput(true);r.email="changed@example.invalid";r.phone="13900000000";contacts.save(2,id,r);
        assertEquals("NONE",contacts.contact(2,id).get("email_verification"));assertEquals("NONE",contacts.contact(2,id).get("phone_verification"));
        notices.deliverPending();assertEquals("CANCELLED",taskStatus(task));
    }
    @Test void unauthorizedOrDeletedContactsCannotReceive() {
        long id=contact(false,true);repeatedMeasurement(110);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE recipient_type='CONTACT'",Integer.class));
        measurement(78);EmergencyContactService.Input r=contactInput(true);contacts.save(2,id,r);repeatedMeasurement(110);long task=emailTask();contacts.delete(2,id);notices.deliverPending();assertEquals("CANCELLED",taskStatus(task));
    }
    @Test void unverifiedAndDemoVerifiedCannotUseRealSmtp() {
        long id=contact(true,false);repeatedMeasurement(110);long task=emailTask();doReturn(false).when(mail).mockMode();
        notices.deliverPending();assertEquals("SKIPPED",taskStatus(task));verify(mail,never()).send(anyString(),anyString(),anyString());
        jdbc.update("UPDATE health_emergency_contact SET email_verification='DEMO' WHERE id=?",id);
        measurement(78);repeatedMeasurement(110);notices.deliverPending();assertEquals("SKIPPED",taskStatus(emailTask()));verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void realVerificationRequiresTokenDeliveredToMailboxAndSendsMinimalContent() {
        long id=contact(true,false);doReturn(false).when(mail).mockMode();doNothing().when(mail).send(anyString(),anyString(),anyString());
        Map<String,Object> challenge=contacts.challenge(2,id);assertFalse(challenge.containsKey("demoToken"));assertEquals("SMTP",challenge.get("mode"));
        org.mockito.ArgumentCaptor<String> body=org.mockito.ArgumentCaptor.forClass(String.class);verify(mail).send(anyString(),anyString(),body.capture());
        String token=body.getValue().split("\n")[1];assertTrue(contacts.verify(2,id,token));assertEquals("REAL",contacts.contact(2,id).get("email_verification"));
        clearInvocations(mail);repeatedMeasurement(110);notices.deliverPending();assertEquals("SENT",taskStatus(emailTask()));
        verify(mail).send(anyString(),anyString(),argThat(text->!text.contains("110") && !text.contains("heartRate") && !text.contains("测试联系人")));
    }
    @Test void h2ForcesMockEvenWithSmtpConfigurationAndNoDemoProfile() throws Exception {
        try(ServerSocket listener=new ServerSocket(0)) {
            listener.setSoTimeout(200);
            org.springframework.mock.env.MockEnvironment env=new org.springframework.mock.env.MockEnvironment().withProperty("health-notification.mode","SMTP").withProperty("health-notification.smtp.enabled","true")
                    .withProperty("health-notification.smtp.host","127.0.0.1").withProperty("health-notification.smtp.port",String.valueOf(listener.getLocalPort())).withProperty("health-notification.smtp.from","noreply@example.invalid");
            HealthMailTransport transport=new HealthMailTransport(env,jdbc);assertTrue(transport.mockMode());
            assertEquals("SMTP_NOT_CONFIGURED",assertThrows(HealthMailTransport.DeliveryFailure.class,()->transport.send("recipient@example.invalid","test","test")).code);
            assertThrows(java.net.SocketTimeoutException.class,listener::accept);
        }
        assertTrue(mail.mockMode());
    }
    @Test void unauthorizedOrInactiveAssociationCannotViewOrProcess() throws Exception {
        measurement(110);long event=event();String token=JwtUtil.toToken(1,3);
        mvc.perform(get("/doctor-health-alerts/"+event).header("token",token)).andExpect(status().isForbidden());
        mvc.perform(get("/doctor-health-alerts").header("token",token)).andExpect(jsonPath("$.data.total").value(0));
        doctors.authorize(2,1,true);mvc.perform(get("/doctor-health-alerts/"+event).header("token",token)).andExpect(status().isOk());
        jdbc.update("UPDATE doctor_consultation SET status=1 WHERE patient_id=2 AND doctor_id=1");
        mvc.perform(post("/doctor-health-alerts/"+event+"/resolve").header("token",token).contentType("application/json").content("{\"note\":\"test\"}")).andExpect(status().isForbidden());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->doctors.authorize(2,2,true));
    }
    @Test void doctorContextCannotBeReplacedByFrontEndDoctorId() throws Exception {
        doctors.authorize(2,1,true);measurement(110);
        mvc.perform(get("/doctor-health-alerts").param("doctorId","1").header("token",JwtUtil.toToken(2,3))).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/doctor-health-alerts/"+event()+"/acknowledge").header("token",JwtUtil.toToken(2,3)).contentType("application/json").content("{\"doctorId\":1,\"note\":\"test\"}")).andExpect(status().isForbidden());
    }
    @Test void equalDoctorAndUserIdsNeverLeakNotifications() throws Exception {
        doctors.authorize(2,1,true);measurement(110);notices.deliverPending();
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(1,3))).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].recipient_type").value("DOCTOR"));
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(1,2))).andExpect(jsonPath("$.data.total").value(0));
        long doctorTask=jdbc.queryForObject("SELECT id FROM health_notification_task WHERE recipient_type='DOCTOR'",Long.class);
        mvc.perform(put("/health-notifications/"+doctorTask+"/read").header("token",JwtUtil.toToken(1,2))).andExpect(status().isNotFound());
        mvc.perform(put("/health-notifications/"+doctorTask+"/read").header("token",JwtUtil.toToken(1,3))).andExpect(status().isOk());
    }
    @Test void doctorAcknowledgesResolvesAndUpgradeReopensCurrentPhase() throws Exception {
        doctors.authorize(2,1,true);measurement(110);long id=event();String token=JwtUtil.toToken(1,3);
        mvc.perform(post("/doctor-health-alerts/"+id+"/acknowledge").header("token",token).contentType("application/json").content("{\"note\":\"已联系患者\"}")).andExpect(status().isOk());
        mvc.perform(post("/doctor-health-alerts/"+id+"/resolve").header("token",token).contentType("application/json").content("{\"note\":\"已记录处理\"}")).andExpect(status().isOk());
        assertEquals("RESOLVED",doctors.detail(1,id).get("handling"));assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM health_alert_event WHERE id=?",String.class,id));
        measurement(111);measurement(112);assertEquals("OPEN",doctors.detail(1,id).get("handling"));
        mvc.perform(get("/doctor-health-alerts").param("level","REPEATED").param("handling","OPEN").header("token",token)).andExpect(jsonPath("$.data.total").value(1));
        assertEquals(2,((List<?>)doctors.detail(1,id).get("actions")).size());
    }
    @Test void revocationCancelsPendingDoctorDeliveryAndReadAccess() throws Exception {
        doctors.authorize(2,1,true);measurement(110);doctors.authorize(2,1,false);notices.deliverPending();
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE recipient_type='DOCTOR'",String.class));
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(1,3))).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void mailFailureDoesNotLoseMeasurementPredictionOrEventAndRetryIsLimited() {
        long contact=contact(true,true);jdbc.update("UPDATE health_emergency_contact SET email_verification='REAL' WHERE id=?",contact);
        doReturn(false).when(mail).mockMode();doThrow(new HealthMailTransport.DeliveryFailure("SMTP_CONNECT_FAILED",false)).when(mail).send(anyString(),anyString(),anyString());
        measurement(110);measurement(110);PersonalizedRiskResponse r=personal.assessCurrent(2,assessment());long task=emailTask();notices.deliverPending();assertEquals("FAILED",taskStatus(task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE id=?",Integer.class,r.getMeasurementId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM personalized_risk_assessment WHERE id=?",Integer.class,r.getAssessmentId()));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_event",Integer.class));
        notices.retry(2,2,task);notices.retry(2,2,task);assertEquals(3,jdbc.queryForObject("SELECT attempts FROM health_notification_task WHERE id=?",Integer.class,task));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->notices.retry(2,2,task));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_attempt WHERE task_id=?",Integer.class,task));
    }
    @Test void uncertainResultIsNeverBlindlyRetried() {
        long contact=contact(true,true);jdbc.update("UPDATE health_emergency_contact SET email_verification='REAL' WHERE id=?",contact);doReturn(false).when(mail).mockMode();
        doThrow(new HealthMailTransport.DeliveryFailure("SMTP_RESULT_UNKNOWN",true)).when(mail).send(anyString(),anyString(),anyString());
        repeatedMeasurement(110);long task=emailTask();notices.deliverPending();assertEquals("UNKNOWN",taskStatus(task));notices.deliverPending();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->notices.retry(2,2,task));assertEquals(1,jdbc.queryForObject("SELECT attempts FROM health_notification_task WHERE id=?",Integer.class,task));
    }
    @Test void actualAdapterWhitelistFailureIsPersistentWithoutLosingAssessmentOrDuplicatingTasks() {
        long id=contact(true,true);jdbc.update("UPDATE health_emergency_contact SET email_verification='REAL' WHERE id=?",id);
        doReturn(false).when(mail).mockMode(); // Non-demo decision only in this offline test; real send policy is exercised.
        measurement(110);measurement(110);CurrentRiskAssessmentDto input=assessment();PersonalizedRiskResponse result=personal.assessCurrent(2,input);
        long task=emailTask();notices.deliverPending();assertEquals("FAILED",taskStatus(task));
        assertEquals("SMTP_RECIPIENT_NOT_ALLOWED",jdbc.queryForObject("SELECT error_code FROM health_notification_task WHERE id=?",String.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE id=?",Integer.class,result.getMeasurementId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM personalized_risk_assessment WHERE id=?",Integer.class,result.getAssessmentId()));
        personal.assessCurrent(2,input);assertEquals(3,tasks());
        notices.retry(2,2,task);notices.retry(2,2,task);
        assertEquals(3,jdbc.queryForObject("SELECT attempts FROM health_notification_task WHERE id=?",Integer.class,task));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->notices.retry(2,2,task));
    }
    @Test void whitelistAlsoBlocksRealVerificationAndNeverPromotesDemoVerification() {
        long id=contact(true,true);doReturn(false).when(mail).mockMode();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->contacts.challenge(2,id));
        assertEquals("DEMO",contacts.contact(2,id).get("email_verification"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_contact_verification WHERE contact_id=?",Integer.class,id));
    }
    @Test void notificationCaptureFailureDoesNotFailOriginalAssessmentAndWorkerRecovers() {
        contact(true,true);doThrow(new org.springframework.dao.DataAccessResourceFailureException("test unavailable")).when(notices).capture(2);
        PersonalizedRiskResponse r=personal.assessCurrent(2,assessment());assertNotNull(r.getMeasurementId());assertEquals("COMPLETE",alerts.status(2,r.getMeasurementId()).get("status"));assertEquals(0,tasks());
        assertEquals("CAPTURE_FAILED",jdbc.queryForObject("SELECT error_code FROM health_notification_checkpoint WHERE measurement_id=?",String.class,r.getMeasurementId()));
        doCallRealMethod().when(notices).capture(2);notices.capture(2);assertEquals(1,tasks());
    }
    @Test void newWorkerInstanceResumesPendingAndDoesNotResendDeliveredTasks() {
        contact(true,true);repeatedMeasurement(110);assertEquals("PENDING",taskStatus(emailTask()));
        HealthNotificationService restarted=new HealthNotificationService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),doctors,mail);
        restarted.deliverPending();assertEquals("MOCK_SENT",taskStatus(emailTask()));restarted.deliverPending();assertEquals(1,jdbc.queryForObject("SELECT attempts FROM health_notification_task WHERE id=?",Integer.class,emailTask()));
    }
    @Test void interruptedSendBecomesUnknownOnWorkerRestart() {
        contact(true,true);repeatedMeasurement(110);long id=emailTask();
        jdbc.update("UPDATE health_notification_task SET status='SENDING',attempts=1,lease_until=? WHERE id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),id);
        jdbc.update("INSERT INTO health_notification_attempt (task_id,attempt_no,status) VALUES (?,1,'SENDING')",id);
        notices.deliverPending();assertEquals("UNKNOWN",taskStatus(id));assertEquals("WORKER_INTERRUPTED",jdbc.queryForObject("SELECT error_code FROM health_notification_task WHERE id=?",String.class,id));
    }
    @Test void concurrentCaptureAndDeliveryDoNotDuplicate() throws Exception {
        contact(true,true);repeatedMeasurement(110);ExecutorService pool=Executors.newFixedThreadPool(4);
        try {List<Callable<Boolean>> work=new ArrayList<>();for(int i=0;i<4;i++)work.add(()->{notices.capture(2);notices.deliverPending();return true;});for(Future<Boolean> f:pool.invokeAll(work))assertTrue(f.get(20,TimeUnit.SECONDS));assertEquals(3,tasks());assertEquals(1,jdbc.queryForObject("SELECT attempts FROM health_notification_task WHERE id=?",Integer.class,emailTask()));}
        finally {pool.shutdownNow();}
    }
    @Test void businessPaginationExcludesMockWithoutDeletingTasksAndPreservesReadState() throws Exception {
        contact(true,true);doctors.authorize(2,1,true);long measurement=repeatedMeasurement(110);notices.deliverPending();
        int all=tasks();assertEquals(5,all);
        Map<String,Object> result=notices.list(2,2,1,1);assertEquals(4L,result.get("total"));assertEquals(false,result.get("emailServiceEnabled"));
        for(int page=1;page<=4;page++) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)notices.list(2,2,page,1).get("items");
            assertEquals(1,rows.size());assertNotEquals("MOCK_SENT",rows.get(0).get("status"));
        }
        long doctorTask=jdbc.queryForObject("SELECT id FROM health_notification_task WHERE recipient_type='DOCTOR' ORDER BY id DESC LIMIT 1",Long.class);
        notices.read(3,1,doctorTask);
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(1,3))).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].read_at").isNotEmpty());
        assertEquals(all,tasks());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE status='MOCK_SENT'",Integer.class));
        assertEquals("COMPLETE",alerts.status(2,measurement).get("status"));
    }
    @Test void realMailHistoryRemainsVisibleAndUnavailableSendNeverReturnsSuccess() {
        contact(true,true);repeatedMeasurement(110);notices.deliverPending();
        long task=emailTask();jdbc.update("UPDATE health_notification_task SET status='SENT' WHERE id=?",task);
        assertEquals(3L,notices.list(2,2,1,50).get("total"));
        assertEquals("SMTP_NOT_CONFIGURED",assertThrows(HealthMailTransport.DeliveryFailure.class,()->mail.send("test@example.invalid","test","test")).code);
    }
    @Test void taskLogsAreScopedAndContainSafeFailureCodes() throws Exception {
        contact(true,false);repeatedMeasurement(110);notices.deliverPending();
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(2,2))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].error_code").value("EMAIL_NOT_VERIFIED_FOR_MODE"));
        mvc.perform(get("/health-notifications").header("token",JwtUtil.toToken(1001,2))).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/health-notifications/"+emailTask()+"/retry").header("token",JwtUtil.toToken(1001,2))).andExpect(status().isNotFound());
    }
    @Test void mockDecisionCannotBecomeRealSendBetweenPolicyChecks() {
        contact(true,true);repeatedMeasurement(110);
        doReturn(true,false).when(mail).mockMode();
        notices.deliverPending();assertEquals("MOCK_SENT",taskStatus(emailTask()));
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
}
