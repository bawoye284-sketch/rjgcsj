package com.star.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.util.Arrays;

/** The optional real-mail test profile must never target an ordinary project database. */
@Component
@Profile("smtp-integration")
public class SmtpIntegrationGuard implements InitializingBean {
    private final Environment env;
    private final JdbcTemplate jdbc;
    public SmtpIntegrationGuard(Environment env,JdbcTemplate jdbc) { this.env=env;this.jdbc=jdbc; }
    @Override public void afterPropertiesSet() throws Exception {
        if(Arrays.asList(env.getActiveProfiles()).contains("demo")) throw new IllegalStateException("SMTP_TEST_PROFILE_CONFLICT");
        try(Connection connection=jdbc.getDataSource().getConnection()) {
            if(!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                    || connection.getCatalog()==null || !connection.getCatalog().matches("ihms_smtp_test_[a-z0-9_]{1,40}"))
                throw new IllegalStateException("SMTP_TEST_REQUIRES_ISOLATED_MYSQL");
        }
        if(env.getProperty("health-notification.smtp.allowed-recipients","").trim().isEmpty())
            throw new IllegalStateException("SMTP_TEST_ALLOWLIST_REQUIRED");
    }
}
