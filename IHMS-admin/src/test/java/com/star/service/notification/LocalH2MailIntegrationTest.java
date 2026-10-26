package com.star.service.notification;

import com.star.model.dto.risk.VitalMeasurementDto;
import com.star.service.risk.PersonalizedRiskService;
import com.star.service.risk.RiskModelClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.env.Environment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual adapter/policy/task flow, with only the final network sender replaced. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:local-mail-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
    "model-runtime.auto-start=false","health-alert.retry-enabled=false","health-notification.worker-enabled=false","health-sos.worker-enabled=false",
    "health-notification.local-test.enabled=true","health-notification.local-test.user-account=demo_elder",
    "health-notification.mode=SMTP","health-notification.smtp.enabled=true",
    "health-notification.smtp.allowed-recipients=test@example.invalid","health-notification.smtp.from=sender@example.invalid",
    "health-notification.smtp.username=sender@example.invalid","health-notification.smtp.password=offline-test-value"})
@ActiveProfiles({"demo","local-mail-test"})
class LocalH2MailIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired EmergencyContactService contacts;
    @Autowired PersonalizedRiskService personal;
    @Autowired SosService sos;
    @Autowired HealthNotificationService notices;
    @Autowired DoctorAlertService doctors;
    @SpyBean HealthMailTransport mail;
    @MockBean RiskModelClient model;
    JavaMailSenderImpl sender;
    @BeforeEach void setup() {
        reset(mail);
        for(String table:Arrays.asList("health_sos_reminder","health_sos_request","health_sos_event","health_notification_attempt","health_notification_task","health_notification_checkpoint","health_contact_verification","health_emergency_contact","health_doctor_authorization","health_alert_action","health_alert_evaluation","health_alert_event"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM elder_vital_measurement WHERE user_id=2");
        jdbc.update("UPDATE health_model_config SET value_range='60,100' WHERE id=2");
        jdbc.update("UPDATE health_alert_rule SET enabled=1,consecutive_count=3,change_delta=NULL");
        jdbc.update("UPDATE doctor SET status=1 WHERE id=1");jdbc.update("UPDATE doctor_consultation SET status=0 WHERE patient_id=2 AND doctor_id=1");
        sender=spy(new JavaMailSenderImpl());doNothing().when(sender).send(any(SimpleMailMessage.class));
        doReturn(sender).when(mail).newSender();
    }
    long contact(String email) {
        EmergencyContactService.Input input=new EmergencyContactService.Input();input.name="Test";input.relationship="Family";input.email=email;input.phone="";input.authorized=true;
        return contacts.save(2,null,input);
    }
    long verifiedContact() {
        long id=contact("test@example.invalid");assertFalse(mail.mockMode());
        Map<String,Object> challenge=contacts.challenge(2,id);assertFalse(challenge.containsKey("demoToken"));
        org.mockito.ArgumentCaptor<SimpleMailMessage> capture=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());String token=capture.getValue().getText().split("\n")[1];
        assertTrue(contacts.verify(2,id,token));assertEquals("REAL",contacts.contact(2,id).get("email_verification"));
        clearInvocations(sender);return id;
    }
    long measurement(double heartRate) {VitalMeasurementDto m=new VitalMeasurementDto();m.setHeartRate(heartRate);return personal.saveMeasurement(2,m);}
    int emails() {return jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_task WHERE channel='EMAIL'",Integer.class);}
    @Test void thirdConsecutiveAbnormalCreatesExactlyOneEmailAndDoctorsCanHandle() {
        verifiedContact();doctors.authorize(2,1,true);measurement(78);
        measurement(110);notices.deliverPending();assertEquals(0,emails());verify(sender,never()).send(any(SimpleMailMessage.class));
        measurement(111);notices.deliverPending();assertEquals(0,emails());
        measurement(112);notices.deliverPending();assertEquals(1,emails());
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE channel='EMAIL'",String.class));
        measurement(113);notices.capture(2);notices.deliverPending();assertEquals(1,emails());verify(sender,times(1)).send(any(SimpleMailMessage.class));
        long event=jdbc.queryForObject("SELECT event_id FROM health_notification_task WHERE channel='EMAIL'",Long.class);
        doctors.action(1,event,"ACKNOWLEDGED","received");
    }
    @Test void normalAndMissingMeasurementsNeverCountAsThreeAbnormalReadings() {
        verifiedContact();measurement(110);measurement(111);measurement(78);measurement(110);
        VitalMeasurementDto missing=new VitalMeasurementDto();missing.setTemperature(36.7);personal.saveMeasurement(2,missing);
        measurement(111);assertEquals(0,emails());measurement(112);assertEquals(1,emails());
    }
    @Test void sosImmediatelyEmailsOnlyTheSingleVerifiedTargetEvenWhenAnotherContactComesFirst() {
        long other=contact("other@example.invalid");jdbc.update("UPDATE health_emergency_contact SET email_verification='REAL' WHERE id=?",other);
        verifiedContact();doctors.authorize(2,1,true);
        String request=UUID.randomUUID().toString();long event=sos.create(2,request);assertEquals(event,sos.create(2,request));
        assertEquals(1,emails());notices.deliverPending();notices.deliverPending();verify(sender,times(1)).send(any(SimpleMailMessage.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE user_id=2",Integer.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE recipient_type='DOCTOR'",String.class));
    }
    @Test void unverifiedAndDemoVerifiedNeverReachRealSender() {
        long id=contact("test@example.invalid");jdbc.update("UPDATE health_emergency_contact SET email_verification='DEMO' WHERE id=?",id);
        measurement(110);measurement(111);measurement(112);notices.deliverPending();
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE channel='EMAIL'",String.class));
        sos.create(2,UUID.randomUUID().toString());verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void otherMailboxOrOtherAccountCannotRequestVerification() {
        long other=contact("other@example.invalid");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->contacts.challenge(2,other));
        long target=contact("test@example.invalid");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->contacts.challenge(1001,target));
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void newSessionRefusesOldPendingAndLateCapturedOldEventsWithoutDeletingRecords() {
        verifiedContact();measurement(110);measurement(111);measurement(112);
        long id=jdbc.queryForObject("SELECT id FROM health_notification_task WHERE channel='EMAIL'",Long.class);
        HealthMailTransport nextSession=new HealthMailTransport(env,jdbc);
        Map<String,Object> old=jdbc.queryForMap("SELECT * FROM health_notification_task WHERE id=?",id);
        assertFalse(nextSession.permitsTask(old));
        jdbc.update("INSERT INTO health_notification_task (user_id,event_id,measurement_id,recipient_type,recipient_id,recipient_revision,channel,phase) SELECT user_id,event_id,measurement_id,recipient_type,recipient_id,recipient_revision,channel,'TEST_LATE' FROM health_notification_task WHERE id=?",id);
        Map<String,Object> late=jdbc.queryForMap("SELECT * FROM health_notification_task WHERE phase='TEST_LATE'");assertFalse(nextSession.permitsTask(late));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM health_notification_task WHERE id=?",String.class,id));verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void profileOrSwitchAloneCannotEnableH2Sending() {
        for(boolean profile:new boolean[]{true,false}) {
            org.springframework.mock.env.MockEnvironment guarded=new org.springframework.mock.env.MockEnvironment()
                    .withProperty("health-notification.mode","SMTP").withProperty("health-notification.smtp.enabled","true")
                    .withProperty("health-notification.local-test.enabled",String.valueOf(!profile));
            guarded.setActiveProfiles(profile?new String[]{"demo","local-mail-test"}:new String[]{"demo"});
            assertTrue(new HealthMailTransport(guarded,jdbc).mockMode());
        }
    }
    @Test void multipleLocalRecipientsFailBeforeStartupAndDuplicateContactAddressesCannotVerify() {
        org.springframework.mock.env.MockEnvironment invalid=new org.springframework.mock.env.MockEnvironment()
                .withProperty("health-notification.local-test.enabled","true")
                .withProperty("health-notification.smtp.allowed-recipients","one@example.invalid,two@example.invalid");
        invalid.setActiveProfiles("demo","local-mail-test");
        assertThrows(IllegalStateException.class,()->new HealthMailTransport(invalid,jdbc));
        long first=contact("test@example.invalid");contact("test@example.invalid");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->contacts.challenge(2,first));
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void knownFailureRetriesUseExistingLogAndDoNotLoseMeasurementOrSos() {
        verifiedContact();measurement(110);measurement(111);measurement(112);
        doThrow(new org.springframework.mail.MailAuthenticationException("private raw diagnostic")).when(sender).send(any(SimpleMailMessage.class));
        notices.deliverPending();long task=jdbc.queryForObject("SELECT id FROM health_notification_task WHERE channel='EMAIL'",Long.class);
        assertEquals("SMTP_AUTH_FAILED",jdbc.queryForObject("SELECT error_code FROM health_notification_task WHERE id=?",String.class,task));
        notices.retry(2,2,task);notices.retry(2,2,task);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM health_notification_attempt WHERE task_id=?",Integer.class,task));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM elder_vital_measurement WHERE user_id=2",Integer.class));
        long event=sos.create(2,UUID.randomUUID().toString());notices.deliverPending();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_sos_event WHERE event_id=?",Integer.class,event));
    }
    @Test void revokedContactCannotSendAndNewWorkerKeepsCurrentSessionDeduplication() {
        long id=verifiedContact();measurement(110);measurement(111);measurement(112);
        HealthNotificationService restarted=new HealthNotificationService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),doctors,mail);
        restarted.deliverPending();restarted.deliverPending();verify(sender,times(1)).send(any(SimpleMailMessage.class));
        sos.create(2,UUID.randomUUID().toString());jdbc.update("UPDATE health_emergency_contact SET authorized=0 WHERE id=?",id);
        restarted.deliverPending();verify(sender,times(1)).send(any(SimpleMailMessage.class));
    }
}
