package com.star;

import com.star.config.SmtpIntegrationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.sql.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SmtpIntegrationGuardTest {
    void check(String database,String product,boolean whitelist,boolean demo) throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        DatabaseMetaData metadata=mock(DatabaseMetaData.class);when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn(product);
        when(connection.getCatalog()).thenReturn(database);
        MockEnvironment env=new MockEnvironment();env.setActiveProfiles(demo?new String[]{"smtp-integration","demo"}:new String[]{"smtp-integration"});
        if(whitelist)env.withProperty("health-notification.smtp.allowed-recipients","test@example.invalid");
        new SmtpIntegrationGuard(env,new JdbcTemplate(source)).afterPropertiesSet();
    }
    @Test void acceptsOnlyExplicitIsolatedMysqlDatabase() throws Exception { check("ihms_smtp_test_local","MySQL",true,false); }
    @Test void rejectsNormalProjectDatabase() { assertThrows(IllegalStateException.class,()->check("personal_health","MySQL",true,false)); }
    @Test void rejectsH2AndMissingCatalog() {
        assertThrows(IllegalStateException.class,()->check("ihms_smtp_test_local","H2",true,false));
        assertThrows(IllegalStateException.class,()->check(null,"MySQL",true,false));
    }
    @Test void rejectsDemoProfileAndEmptyAllowlist() {
        assertThrows(IllegalStateException.class,()->check("ihms_smtp_test_local","MySQL",true,true));
        assertThrows(IllegalStateException.class,()->check("ihms_smtp_test_local","MySQL",false,false));
    }
}
