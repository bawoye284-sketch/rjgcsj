package com.star.service.notification;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.sql.*;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class HealthMailTransportTest {
    MockEnvironment env;
    JavaMailSenderImpl sender;
    HealthMailTransport transport;
    DatabaseMetaData metadata;
    @BeforeEach void setup() throws Exception {
        env=new MockEnvironment().withProperty("health-notification.mode","SMTP")
                .withProperty("health-notification.smtp.enabled","true")
                .withProperty("health-notification.smtp.host","smtp.qq.com")
                .withProperty("health-notification.smtp.from","sender@example.invalid")
                .withProperty("health-notification.smtp.username","sender@example.invalid")
                .withProperty("health-notification.smtp.password","unit-test-not-a-secret")
                .withProperty("health-notification.smtp.security","SSL_TLS")
                .withProperty("health-notification.smtp.allowed-recipients","test@example.invalid");
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        metadata=mock(DatabaseMetaData.class);when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        sender=spy(new JavaMailSenderImpl());doNothing().when(sender).send(any(SimpleMailMessage.class));
        transport=new HealthMailTransport(env,new JdbcTemplate(source)) {
            @Override JavaMailSenderImpl newSender() { return sender; }
        };
    }
    void send() { transport.send("test@example.invalid","test","no patient data"); }
    @Test void qqUses465ImplicitTlsWithCertificateChecks() {
        send();assertEquals(465,sender.getPort());assertEquals("smtp.qq.com",sender.getHost());
        assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("false",sender.getJavaMailProperties().getProperty("mail.smtp.starttls.required"));
        assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("false",sender.getJavaMailProperties().getProperty("mail.debug"));
        verify(sender).send(any(SimpleMailMessage.class));
    }
    @Test void starttlsRemainsRequiredByDefault() {
        ((org.springframework.core.env.MapPropertySource)env.getPropertySources().get("mockProperties")).getSource()
                .remove("health-notification.smtp.security");
        send();assertEquals(587,sender.getPort());
        assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.starttls.required"));
        assertEquals("false",sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
    }
    @Test void h2CannotSendEvenWithSmtpAndWhitelistEnabled() throws Exception {
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        assertTrue(transport.mockMode());assertFalse(transport.enabledForDelivery());
        assertEquals("SMTP_NOT_CONFIGURED",assertThrows(HealthMailTransport.DeliveryFailure.class,this::send).code);
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void demoCannotSendEvenOnMysql() {
        env.setActiveProfiles("demo");assertTrue(transport.mockMode());
        assertThrows(HealthMailTransport.DeliveryFailure.class,this::send);verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void missingDatabaseFailsClosed() throws Exception {
        when(metadata.getDatabaseProductName()).thenThrow(new SQLException("not logged"));
        assertTrue(transport.mockMode());assertThrows(HealthMailTransport.DeliveryFailure.class,this::send);
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void allowlistIsMandatoryAndExact() {
        for(String allowed:new String[]{"","*@example.invalid","other@example.invalid","Name <test@example.invalid>"}) {
            env.withProperty("health-notification.smtp.allowed-recipients",allowed);
            assertEquals("SMTP_RECIPIENT_NOT_ALLOWED",assertThrows(HealthMailTransport.DeliveryFailure.class,this::send).code);
        }
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void multipleRecipientsAndHeaderInjectionAreRejected() {
        for(String recipient:new String[]{"test@example.invalid,other@example.invalid","test@example.invalid\r\nBcc:other@example.invalid"}) {
            assertThrows(HealthMailTransport.DeliveryFailure.class,()->transport.send(recipient,"test","test"));
        }
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void whitelistSupportsExplicitSeparatedMailboxes() {
        env.withProperty("health-notification.smtp.allowed-recipients","other@example.invalid, TEST@example.invalid");send();
        verify(sender).send(any(SimpleMailMessage.class));
    }
    @Test void invalidSecurityNeverFallsBackToPlaintext() {
        env.withProperty("health-notification.smtp.security","NONE");assertFalse(transport.enabledForDelivery());
        assertEquals("SMTP_SECURITY_INVALID",assertThrows(HealthMailTransport.DeliveryFailure.class,this::send).code);
        verify(sender,never()).send(any(SimpleMailMessage.class));
    }
    @Test void authenticationFailureIsRedactedAndRetryable() {
        doThrow(new MailAuthenticationException("raw confidential diagnostic")).when(sender).send(any(SimpleMailMessage.class));
        HealthMailTransport.DeliveryFailure error=assertThrows(HealthMailTransport.DeliveryFailure.class,this::send);
        assertEquals("SMTP_AUTH_FAILED",error.getMessage());assertFalse(error.uncertain);assertNull(error.getCause());
    }
    @Test void timeoutIsUncertainAndNeverReportedAsAccepted() {
        doThrow(new MailSendException("raw confidential diagnostic",new SocketTimeoutException())).when(sender).send(any(SimpleMailMessage.class));
        HealthMailTransport.DeliveryFailure error=assertThrows(HealthMailTransport.DeliveryFailure.class,this::send);
        assertEquals("SMTP_RESULT_UNKNOWN",error.code);assertTrue(error.uncertain);
    }
}
