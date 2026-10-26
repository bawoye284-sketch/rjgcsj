package com.star;

import com.alibaba.fastjson2.JSON;
import com.star.context.LocalThreadHolder;
import com.star.service.notification.*;
import com.star.service.risk.RiskModelClient;
import com.star.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:sos-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "model-runtime.auto-start=false","health-alert.retry-enabled=false","health-notification.worker-enabled=false","health-sos.worker-enabled=false",
        "health-notification.mode=SMTP","health-notification.smtp.enabled=true"})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class SosIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SosService sos;
    @Autowired EmergencyContactService contacts;
    @Autowired DoctorAlertService doctors;
    @Autowired Environment env;
    @Autowired MockMvc mvc;
    @SpyBean HealthNotificationService notices;
    @SpyBean HealthMailTransport mail;
    @MockBean RiskModelClient model;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM health_sos_reminder");
        reset(notices,mail,model);
        for(String table:Arrays.asList("health_sos_request","health_sos_event","health_notification_attempt","health_notification_task","health_notification_checkpoint","health_contact_verification","health_emergency_contact","health_doctor_authorization","health_alert_action","health_alert_evaluation","health_alert_event"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE doctor SET status=1 WHERE id IN (1,2)");jdbc.update("UPDATE doctor_consultation SET status=0 WHERE id IN (1,1001,1002,1003)");
    }
    @AfterEach void clear() {LocalThreadHolder.clear();}
    long contact() {
        EmergencyContactService.Input r=new EmergencyContactService.Input();r.name="SOS演示联系人";r.relationship="家属";r.email="sos@example.invalid";r.phone="";r.authorized=true;
        long id=contacts.save(2,null,r);String token=contacts.challenge(2,id).get("demoToken").toString();assertTrue(contacts.verify(2,id,token));return id;
    }
    long create() {return sos.create(2,UUID.randomUUID().toString());}
    int events() {return jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_event",Integer.class);}
    int tasks(long id) {return jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=?",Integer.class,id);}
    void due(long id) {Timestamp past=Timestamp.valueOf(LocalDateTime.now().minusMinutes(1));jdbc.update("UPDATE health_sos_event SET confirmation_deadline=?,next_check_at=? WHERE event_id=?",past,past,id);}
    void escalate(long id) {due(id);sos.advance(id);sos.planSafely(id);}

    @Test void userEndpointCreatesIndependentHighestPriorityEvent() throws Exception {
        contact();doctors.authorize(2,1,true);
        int measurements=jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class);
        String result=mvc.perform(post("/sos").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content("{\"requestId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        long id=JSON.parseObject(result).getJSONObject("data").getLongValue("id");
        assertEquals(1,events());assertEquals(3,tasks(id));
        assertEquals("EMERGENCY",jdbc.queryForObject("SELECT level FROM health_alert_event WHERE id=?",String.class,id));
        assertNull(jdbc.queryForObject("SELECT first_measurement_id FROM health_alert_event WHERE id=?",Long.class,id));
        assertEquals(measurements,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class));verifyNoInteractions(model);
        assertEquals(100,jdbc.queryForObject("SELECT MIN(priority) FROM health_notification_task WHERE event_id=?",Integer.class,id));
    }
    @Test void identicalAndDifferentRequestsReuseOpenEvent() {
        String request=UUID.randomUUID().toString();long id=sos.create(2,request);
        assertEquals(id,sos.create(2,request));assertEquals(id,sos.create(2,request.toUpperCase(Locale.ROOT)));assertEquals(id,create());assertEquals(1,events());assertEquals(1,tasks(id));
    }
    void allowReminder(long id) {jdbc.update("UPDATE health_alert_event SET opened_at=? WHERE id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)),id);}
    @Test void activeReminderIsDurableIdempotentTypedAndRefreshDoesNotSend() {
        contact();doctors.authorize(2,1,true);long id=create();allowReminder(id);String key=UUID.randomUUID().toString();
        assertEquals(id,sos.remind(2,id,key));assertEquals(id,sos.remind(2,id,key.toUpperCase(Locale.ROOT)));assertEquals(1,events());assertEquals(6,tasks(id));
        for(int i=0;i<3;i++)sos.current(2);assertEquals(6,tasks(id));
        notices.deliverPending();assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND recipient_type='DOCTOR' AND status='SENT'",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT' AND status='MOCK_SENT'",Integer.class,id));
        doctors.action(1,id,"ACKNOWLEDGED","收到");assertEquals("ACKNOWLEDGED",sos.detail(2,id).get("status"));
        doctors.action(1,id,"RESOLVED","处理完成");assertNotEquals(id,create());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sos.remind(2,id,UUID.randomUUID().toString()));verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void reminderIntervalHourlyAndEventLimitsCannotBeBypassed() {
        long id=create();assertFalse((Boolean)sos.detail(2,id).get("reminderAllowed"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sos.remind(2,id,UUID.randomUUID().toString()));allowReminder(id);
        sos.remind(2,id,UUID.randomUUID().toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sos.remind(2,id,UUID.randomUUID().toString()));
        for(int i=0;i<2;i++){jdbc.update("UPDATE health_sos_reminder SET created_at=? WHERE event_id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)),id);sos.remind(2,id,UUID.randomUUID().toString());}
        jdbc.update("UPDATE health_sos_reminder SET created_at=? WHERE event_id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)),id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sos.remind(2,id,UUID.randomUUID().toString()));
        org.springframework.test.util.ReflectionTestUtils.setField(sos,"reminderEventLimit",1);
        try {assertEquals(true,sos.detail(2,id).get("reminderLimitReached"));}finally{org.springframework.test.util.ReflectionTestUtils.setField(sos,"reminderEventLimit",20);}
    }
    @Test void concurrentRemindersShareOneRecordAndPhase() throws Exception {
        long id=create();allowReminder(id);String key=UUID.randomUUID().toString();ExecutorService pool=Executors.newFixedThreadPool(4);
        try {List<Callable<Long>> work=new ArrayList<>();for(int i=0;i<4;i++)work.add(()->sos.remind(2,id,key));for(Future<Long> f:pool.invokeAll(work))assertEquals(id,f.get(20,TimeUnit.SECONDS));assertEquals(2,tasks(id));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_reminder",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void failedReminderPlanningSurvivesWorkerRecovery() {
        contact();long id=create();allowReminder(id);String key=UUID.randomUUID().toString();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test")).when(notices).captureReminder(anyLong());
        assertEquals(id,sos.remind(2,id,key));assertEquals(1,sos.detail(2,id).get("reminderPending"));
        doCallRealMethod().when(notices).captureReminder(anyLong());
        org.springframework.test.util.ReflectionTestUtils.setField(sos,"workerEnabled",true);
        try{sos.tick();sos.tick();assertEquals(0,sos.detail(2,id).get("reminderPending"));assertEquals(4,tasks(id));}finally{org.springframework.test.util.ReflectionTestUtils.setField(sos,"workerEnabled",false);}
    }
    @Test void reminderEndpointEnforcesOwnershipAndSupportsAcknowledgedEvent() throws Exception {
        doctors.authorize(2,1,true);long id=create();allowReminder(id);doctors.action(1,id,"ACKNOWLEDGED","已确认");
        String body="{\"requestId\":\""+UUID.randomUUID()+"\"}";
        mvc.perform(post("/sos/"+id+"/reminders").header("token",JwtUtil.toToken(1001,2)).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/sos/"+id+"/reminders").header("token",JwtUtil.toToken(2,3)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/sos/"+id+"/reminders").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
    }
    @Test void acknowledgedEventStillPreventsDuplicateActiveEvent() {
        doctors.authorize(2,1,true);long id=create();doctors.action(1,id,"ACKNOWLEDGED","演示确认");assertEquals(id,create());assertEquals(1,events());
    }
    @Test void resolvedRequestReplaysOldEventButNewRequestCanStartNewOne() {
        doctors.authorize(2,1,true);String request=UUID.randomUUID().toString();long first=sos.create(2,request);doctors.action(1,first,"RESOLVED","演示处理");
        assertEquals(first,sos.create(2,request));assertNotEquals(first,create());assertEquals(2,events());
    }
    @Test void concurrentRequestsCreateOnlyOneEventAndOneInitialStage() throws Exception {
        contact();doctors.authorize(2,1,true);ExecutorService pool=Executors.newFixedThreadPool(4);
        try {List<Callable<Long>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(this::create);Set<Long> ids=new HashSet<>();for(Future<Long> f:pool.invokeAll(jobs))ids.add(f.get(20,TimeUnit.SECONDS));assertEquals(1,ids.size());assertEquals(1,events());assertEquals(3,tasks(ids.iterator().next()));}
        finally {pool.shutdownNow();}
    }
    @Test void initialNotifiesFirstContactAndEscalationNotifiesOtherAuthorizedContacts() {
        long first=contact(),second=contact(),third=contact();doctors.authorize(2,1,true);long id=create();
        assertEquals(first,jdbc.queryForObject("SELECT recipient_id FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT'",Long.class,id));
        escalate(id);assertEquals(6,tasks(id));assertEquals(second,jdbc.queryForObject("SELECT recipient_id FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT' AND phase='SOS_ESC_1'",Long.class,id));
        escalate(id);assertEquals(third,jdbc.queryForObject("SELECT recipient_id FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT' AND phase='SOS_ESC_2'",Long.class,id));
        assertEquals(102,jdbc.queryForObject("SELECT MAX(priority) FROM health_notification_task WHERE event_id=?",Integer.class,id));
    }
    @Test void notDueDoesNotEscalateAndStageCannotDuplicate() {
        contact();contact();long id=create();sos.advance(id);sos.planSafely(id);assertEquals(0,sos.detail(2,id).get("escalation_stage"));
        escalate(id);int count=tasks(id);sos.planSafely(id);sos.advance(id);assertEquals(count,tasks(id));assertEquals(1,sos.detail(2,id).get("escalation_stage"));
    }
    @Test void configuredMaximumStopsEscalations() {
        long id=create();escalate(id);escalate(id);escalate(id);assertEquals(2,sos.detail(2,id).get("escalation_stage"));assertEquals(3,tasks(id));
    }
    @Test void acknowledgedSosStopsEscalationAndCancelsPendingEscalationTasks() {
        contact();contact();doctors.authorize(2,1,true);long id=create();escalate(id);
        doctors.action(1,id,"ACKNOWLEDGED","已确认");int before=tasks(id);escalate(id);assertEquals(before,tasks(id));
        notices.deliverPending();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND phase LIKE 'SOS_ESC_%' AND status IN ('SENT','MOCK_SENT')",Integer.class,id));
        assertEquals("ACKNOWLEDGED",sos.detail(2,id).get("status"));
    }
    @Test void resolvedSosNeverEscalatesAndDoctorActionIsIdempotent() {
        doctors.authorize(2,1,true);long id=create();doctors.action(1,id,"RESOLVED","已处理");doctors.action(1,id,"RESOLVED","重复处理");escalate(id);
        assertEquals(0,sos.detail(2,id).get("escalation_stage"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_alert_action WHERE event_id=?",Integer.class,id));
        assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM health_alert_event WHERE id=?",String.class,id));
    }
    @Test void unauthorizedDoctorsAndMatchingUserIdCannotAccessSos() throws Exception {
        long id=create();String doctor=JwtUtil.toToken(1,3);
        mvc.perform(get("/doctor-health-alerts/"+id).header("token",doctor)).andExpect(status().isForbidden());
        mvc.perform(post("/doctor-health-alerts/"+id+"/resolve").header("token",doctor).contentType("application/json").content("{\"note\":\"无授权\"}")).andExpect(status().isForbidden());
        doctors.authorize(2,1,true);
        mvc.perform(get("/doctor-health-alerts/"+id).header("token",doctor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("SOS"));
        mvc.perform(get("/sos/"+id).header("token",JwtUtil.toToken(1,2))).andExpect(status().isNotFound());
        mvc.perform(get("/sos/current").header("token",doctor)).andExpect(status().isForbidden());
        mvc.perform(get("/doctor-health-alerts").header("token",JwtUtil.toToken(1,2))).andExpect(status().isForbidden());
    }
    @Test void doctorCanAcknowledgeAndResolveViaExistingEndpoints() throws Exception {
        doctors.authorize(2,1,true);long id=create();String token=JwtUtil.toToken(1,3);
        mvc.perform(get("/doctor-health-alerts").param("level","EMERGENCY").header("token",token)).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/doctor-health-alerts/"+id+"/acknowledge").header("token",token).contentType("application/json").content("{\"note\":\"已联系\"}")).andExpect(status().isOk());
        assertNotNull(sos.detail(2,id).get("acknowledged_at"));
        mvc.perform(post("/doctor-health-alerts/"+id+"/resolve").header("token",token).contentType("application/json").content("{\"note\":\"已记录\"}")).andExpect(status().isOk());assertEquals("RESOLVED",sos.detail(2,id).get("status"));
    }
    @Test void mockPolicyAndDeliveryNeverCallRealMailAdapter() throws Exception {
        contact();long id=create();assertEquals(true,sos.policy().get("demo"));notices.deliverPending();
        assertEquals("MOCK_SENT",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT'",String.class,id));verify(mail,never()).send(anyString(),anyString(),anyString());
        mvc.perform(get("/sos/policy").header("token",JwtUtil.toToken(2,2))).andExpect(jsonPath("$.data.demo").value(true));
    }
    @Test void mailFailureDoesNotRollBackSosOrTasks() {
        long contact=contact();jdbc.update("UPDATE health_emergency_contact SET email_verification='REAL' WHERE id=?",contact);
        doReturn(false).when(mail).mockMode();doThrow(new HealthMailTransport.DeliveryFailure("SMTP_CONNECT_FAILED",false)).when(mail).send(anyString(),anyString(),anyString());
        long id=create();notices.deliverPending();assertEquals("OPEN",sos.detail(2,id).get("status"));assertEquals(2,tasks(id));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT'",String.class,id));
    }
    @Test void planningFailurePreservesSosAndCanResume() {
        contact();doThrow(new org.springframework.dao.DataAccessResourceFailureException("test")).when(notices).captureSos(anyLong(),anyInt(),anyInt());long id=create();
        assertEquals(1,events());assertEquals("SOS_CAPTURE_FAILED",sos.detail(2,id).get("notification_error"));assertEquals(0,tasks(id));
        doCallRealMethod().when(notices).captureSos(anyLong(),anyInt(),anyInt());sos.planSafely(id);assertEquals(2,tasks(id));assertNull(sos.detail(2,id).get("notification_error"));
    }
    @Test void revokedContactReceivesNoQueuedOrFutureEscalationNotice() {
        long first=contact(),second=contact();long id=create();
        jdbc.update("UPDATE health_emergency_contact SET authorized=0 WHERE id IN (?,?)",first,second);
        notices.deliverPending();assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT'",String.class,id));
        escalate(id);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND recipient_type='CONTACT' AND phase='SOS_ESC_1'",Integer.class,id));
    }
    @Test void unverifiedContactsAreNotSelectedAndRevokedDoctorCannotHandle() {
        long contact=contact();jdbc.update("UPDATE health_emergency_contact SET email_verification='NONE' WHERE id=?",contact);doctors.authorize(2,1,true);long id=create();assertEquals(2,tasks(id));
        doctors.authorize(2,1,false);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->doctors.action(1,id,"ACKNOWLEDGED","无权"));
    }
    @Test void restartInstanceResumesDeadlinesAndPersistedNotifications() {
        contact();contact();long id=create();due(id);
        SosService restarted=new SosService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),notices,env);
        ReflectionTestUtils.setField(restarted,"workerEnabled",true);ReflectionTestUtils.setField(restarted,"maxEscalations",2);ReflectionTestUtils.setField(restarted,"escalationSeconds",120);ReflectionTestUtils.setField(restarted,"initialContacts",1);ReflectionTestUtils.setField(restarted,"escalationContacts",1);
        restarted.tick();notices.deliverPending();assertEquals(1,sos.detail(2,id).get("escalation_stage"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND status='MOCK_SENT'",Integer.class,id));
    }
    @Test void ownHistoryAndNotificationStateAreSeparatedFromMeasurementHistory() throws Exception {
        contact();long id=create();String token=JwtUtil.toToken(2,2);
        mvc.perform(get("/sos/current").header("token",token)).andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.notifications[0].status").value("PENDING"));
        notices.deliverPending();mvc.perform(get("/sos/"+id).header("token",token)).andExpect(jsonPath("$.data.notifications.length()").value(1)).andExpect(jsonPath("$.data.notifications[0].status").value("SENT")).andExpect(jsonPath("$.data.emailServiceEnabled").value(false));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND status='MOCK_SENT'",Integer.class,id));
        mvc.perform(get("/health-alerts").header("token",token)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/sos").header("token",token)).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void schemaUpgradeRepeatsWithoutLosingOldRecords() {
        int old=jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class);long id=create();
        org.springframework.jdbc.datasource.init.ResourceDatabasePopulator upgrade=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("demo/schema.sql"));upgrade.execute(jdbc.getDataSource());upgrade.execute(jdbc.getDataSource());
        assertEquals(old,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class));assertEquals("OPEN",sos.detail(2,id).get("status"));assertEquals(1,events());
    }
    @Test void invalidRequestAndForeignOwnerCannotCreateOrRead() throws Exception {
        mvc.perform(post("/sos").header("token",JwtUtil.toToken(2,2)).contentType("application/json").content("{\"requestId\":\"bad\"}")).andExpect(status().isBadRequest());
        long id=create();mvc.perform(get("/sos/"+id).header("token",JwtUtil.toToken(1001,2))).andExpect(status().isNotFound());assertEquals(1,events());
    }
    @Test void oldPhaseThreeDatabaseUpgradesKeepingEventsAndTasks() throws Exception {
        String schema=org.springframework.util.StreamUtils.copyToString(new org.springframework.core.io.ClassPathResource("demo/schema.sql").getInputStream(),java.nio.charset.StandardCharsets.UTF_8);
        String previous=schema.substring(0,schema.indexOf("-- SOS (phase 4)"));
        try(java.sql.Connection connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:sos-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE","sa","")) {
            org.springframework.jdbc.datasource.SingleConnectionDataSource data=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(previous.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new org.springframework.core.io.ClassPathResource("demo/data.sql")).execute(data);
            JdbcTemplate old=new JdbcTemplate(data);
            old.update("INSERT INTO health_alert_event (id,user_id,metric_key,level,status,first_measurement_id,last_measurement_id,details_json,opened_at,updated_at) VALUES (90001,2,'heart_rate','GENERAL','ACTIVE',1,1,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            old.update("INSERT INTO health_notification_task (user_id,event_id,measurement_id,recipient_type,recipient_id,channel,phase) VALUES (2,90001,1,'USER',2,'IN_APP','GENERAL')");
            int count=old.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class);
            org.springframework.jdbc.datasource.init.ResourceDatabasePopulator upgrade=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("demo/schema.sql"));upgrade.execute(data);upgrade.execute(data);
            assertEquals(count,old.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement",Integer.class));
            assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM health_alert_event WHERE id=90001 AND first_measurement_id=1",Integer.class));
            assertEquals(0,old.queryForObject("SELECT priority FROM health_notification_task WHERE event_id=90001",Integer.class));
        }
    }
    @Test void resolutionCancelsLateInitialMessagesAndClearsPlanningError() {
        contact();doctors.authorize(2,1,true);long id=create();
        jdbc.update("UPDATE health_sos_event SET notification_error='SOS_CAPTURE_FAILED' WHERE event_id=?",id);
        doctors.action(1,id,"RESOLVED","已完成处理");notices.deliverPending();
        assertNull(sos.detail(2,id).get("notification_error"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE event_id=? AND status IN ('SENT','MOCK_SENT','PENDING')",Integer.class,id));
    }
}
