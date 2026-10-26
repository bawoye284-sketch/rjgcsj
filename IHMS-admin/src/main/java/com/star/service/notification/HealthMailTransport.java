package com.star.service.notification;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Properties;
import java.util.Locale;
import javax.mail.internet.InternetAddress;
import org.springframework.context.annotation.DependsOn;
import java.util.Map;

/** No Spring mail auto-send; our own opt-in and database/profile guard always apply. */
@Component
@DependsOn("healthNotificationSchema")
public class HealthMailTransport {
    private final Environment env;
    private final JdbcTemplate jdbc;
    private final boolean localTest;
    private final int localUser;
    private final long eventWatermark,taskWatermark,measurementWatermark;
    private final String localRecipient;
    public HealthMailTransport(Environment env, JdbcTemplate jdbc) {
        this.env=env;this.jdbc=jdbc;
        localTest=Arrays.asList(env.getActiveProfiles()).contains("local-mail-test")
                && env.getProperty("health-notification.local-test.enabled",Boolean.class,false);
        if(localTest) {
            localRecipient=mailbox(env.getProperty("health-notification.smtp.allowed-recipients",""));
            if(localRecipient.isEmpty())throw new IllegalStateException("LOCAL_MAIL_REQUIRES_SINGLE_RECIPIENT");
            try {
                localUser=jdbc.queryForObject("SELECT id FROM `user` WHERE user_account=? AND user_role=2",Integer.class,
                        env.getProperty("health-notification.local-test.user-account",""));
                eventWatermark=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM health_alert_event",Long.class);
                taskWatermark=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM health_notification_task",Long.class);
                measurementWatermark=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM elder_vital_measurement",Long.class);
            } catch(RuntimeException invalid) { throw new IllegalStateException("LOCAL_MAIL_SCOPE_UNAVAILABLE"); }
        } else {localRecipient="";localUser=0;eventWatermark=0;taskWatermark=0;measurementWatermark=0;}
    }
    public boolean mockMode() {
        if(!"SMTP".equalsIgnoreCase(env.getProperty("health-notification.mode","MOCK"))
                || !env.getProperty("health-notification.smtp.enabled",Boolean.class,false)) return true;
        // Check actual driver, not just a user-supplied profile or URL.
        try(Connection c=jdbc.getDataSource().getConnection()) {
            boolean h2="H2".equalsIgnoreCase(c.getMetaData().getDatabaseProductName());
            if(localTest && h2)return false; // Explicit scoped test exception; never the default.
            return h2 || Arrays.asList(env.getActiveProfiles()).contains("demo");
        }
        catch(Exception unavailable) { return true; }
    }
    public boolean localTestMode() { return localTest; }
    public Object[] localOutboxScope() {return new Object[]{taskWatermark,eventWatermark,measurementWatermark,localUser};}
    public boolean permitsContact(int user,long contact) {
        if(!localTest)return true;
        if(user!=localUser)return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM health_emergency_contact WHERE user_id=? AND authorized=1 AND LOWER(email)=?",Integer.class,user,localRecipient)==1
                && jdbc.queryForObject("SELECT COUNT(*) FROM health_emergency_contact WHERE id=? AND user_id=? AND authorized=1 AND LOWER(email)=?",Integer.class,contact,user,localRecipient)==1;
    }
    public boolean permitsTask(Map<String,Object> task) {
        if(!localTest || !"EMAIL".equals(task.get("channel")))return true;
        return "CONTACT".equals(task.get("recipient_type")) && ((Number)task.get("user_id")).intValue()==localUser
                && ((Number)task.get("id")).longValue()>taskWatermark && ((Number)task.get("event_id")).longValue()>eventWatermark
                && (task.get("measurement_id")==null || ((Number)task.get("measurement_id")).longValue()>measurementWatermark)
                && permitsContact(localUser,((Number)task.get("recipient_id")).longValue());
    }
    public void checkVerificationScope(int user,long contact) {
        if(!permitsContact(user,contact))throw new DeliveryFailure("SMTP_RECIPIENT_NOT_ALLOWED",false);
    }
    public static class DeliveryFailure extends RuntimeException {
        public final String code;
        public final boolean uncertain;
        public DeliveryFailure(String code,boolean uncertain) { super(code);this.code=code;this.uncertain=uncertain; }
    }
    public boolean enabledForDelivery() {
        return !mockMode() && !env.getProperty("health-notification.smtp.host","").trim().isEmpty()
                && !env.getProperty("health-notification.smtp.from","").trim().isEmpty()
                && !env.getProperty("health-notification.smtp.allowed-recipients","").trim().isEmpty()
                && Arrays.asList("SSL_TLS","STARTTLS").contains(security());
    }
    private String security() { return env.getProperty("health-notification.smtp.security","STARTTLS").trim().toUpperCase(Locale.ROOT); }
    private String mailbox(String value) {
        try {
            InternetAddress parsed=new InternetAddress(value,true);parsed.validate();
            // A single plain mailbox only; no display names, lists, headers or wildcard rules.
            if(!value.equals(parsed.getAddress()) || value.contains("\r") || value.contains("\n")) return "";
            return parsed.getAddress().toLowerCase(Locale.ROOT);
        } catch(Exception invalid) { return ""; }
    }
    private void requireAllowed(String address) {
        String target=mailbox(address);
        if(!target.isEmpty()) {
            for(String entry:env.getProperty("health-notification.smtp.allowed-recipients","").split(",")) {
                String allowed=mailbox(entry.trim());
                if(!allowed.isEmpty() && allowed.equals(target)) return;
            }
        }
        throw new DeliveryFailure("SMTP_RECIPIENT_NOT_ALLOWED",false);
    }
    // Package-local seam for offline transport tests, never a bypass of the send policy.
    JavaMailSenderImpl newSender() { return new JavaMailSenderImpl(); }
    public void send(String address,String subject,String body) {
        // A caller must not mistake a blocked/no-op send for actual SMTP acceptance.
        if(mockMode()) throw new DeliveryFailure("SMTP_NOT_CONFIGURED",false);
        requireAllowed(address); // Also applies to real mailbox-verification messages.
        String host=env.getProperty("health-notification.smtp.host","");
        String from=env.getProperty("health-notification.smtp.from","");
        if(host.isEmpty() || from.isEmpty()) throw new DeliveryFailure("SMTP_NOT_CONFIGURED",false);
        String security=security();
        if(!Arrays.asList("SSL_TLS","STARTTLS").contains(security)) throw new DeliveryFailure("SMTP_SECURITY_INVALID",false);
        JavaMailSenderImpl sender=newSender();
        sender.setHost(host);sender.setPort(env.getProperty("health-notification.smtp.port",Integer.class,"SSL_TLS".equals(security)?465:587));
        sender.setUsername(env.getProperty("health-notification.smtp.username",""));
        sender.setPassword(env.getProperty("health-notification.smtp.password",""));
        sender.setDefaultEncoding("UTF-8");
        Properties props=sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth",String.valueOf(!sender.getUsername().isEmpty()));
        boolean ssl="SSL_TLS".equals(security);
        props.setProperty("mail.smtp.ssl.enable",String.valueOf(ssl));
        props.setProperty("mail.smtp.starttls.enable",String.valueOf(!ssl));props.setProperty("mail.smtp.starttls.required",String.valueOf(!ssl));
        props.setProperty("mail.smtp.ssl.protocols","TLSv1.2 TLSv1.3");
        props.setProperty("mail.smtp.ssl.checkserveridentity","true");
        props.setProperty("mail.smtp.connectiontimeout","5000");props.setProperty("mail.smtp.timeout","5000");props.setProperty("mail.smtp.writetimeout","5000");
        props.setProperty("mail.debug","false");
        SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(address);message.setSubject(subject);message.setText(body);
        try { sender.send(message); }
        catch(MailAuthenticationException denied) { throw new DeliveryFailure("SMTP_AUTH_FAILED",false); }
        catch(RuntimeException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SocketTimeoutException) throw new DeliveryFailure("SMTP_RESULT_UNKNOWN",true);
                if(cause instanceof ConnectException) throw new DeliveryFailure("SMTP_CONNECT_FAILED",false);
            }
            // Partial delivery, TLS errors, I/O after submission: never blindly send again.
            throw new DeliveryFailure("SMTP_RESULT_UNKNOWN",true);
        }
    }
}
