package com.star.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Additive compatibility repair for installations created before notification priority existed. */
@Component("healthNotificationSchema")
@DependsOnDatabaseInitialization
public class HealthNotificationSchema implements InitializingBean {
    private final JdbcTemplate jdbc;
    public HealthNotificationSchema(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void afterPropertiesSet() {
        Boolean bindingPresent = jdbc.query("SELECT * FROM health_doctor_authorization WHERE 1=0", rs -> {
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++)
                if ("bound".equalsIgnoreCase(rs.getMetaData().getColumnLabel(i))) return true;
            return false;
        });
        if (!Boolean.TRUE.equals(bindingPresent))
            jdbc.execute("ALTER TABLE health_doctor_authorization ADD COLUMN bound INT NOT NULL DEFAULT 0");
        Boolean present = jdbc.query("SELECT * FROM health_notification_task WHERE 1=0", rs -> {
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++)
                if ("priority".equalsIgnoreCase(rs.getMetaData().getColumnLabel(i))) return true;
            return false;
        });
        if (!Boolean.TRUE.equals(present))
            jdbc.execute("ALTER TABLE health_notification_task ADD COLUMN priority INT NOT NULL DEFAULT 0");
    }
}
