package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TestDatabaseIsolationTests {
    @Test
    void contextsShareServerButKeepIndependentMigratedDatabasesAfterContextClose()
            throws Exception {
        JdbcConnectionDetails first;
        try (var context =
                new AnnotationConfigApplicationContext(TestcontainersConfiguration.class)) {
            first = context.getBean(JdbcConnectionDetails.class);
            try (var connection =
                            DriverManager.getConnection(
                                    first.getJdbcUrl(), first.getUsername(), first.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.context_marker(id integer)");
            }
        }

        try (var context =
                new AnnotationConfigApplicationContext(TestcontainersConfiguration.class)) {
            var second = context.getBean(JdbcConnectionDetails.class);
            assertThat(second.getJdbcUrl()).isNotEqualTo(first.getJdbcUrl());
            assertThat(second.getJdbcUrl().substring(0, second.getJdbcUrl().lastIndexOf('/')))
                    .isEqualTo(
                            first.getJdbcUrl().substring(0, first.getJdbcUrl().lastIndexOf('/')));
            try (var connection =
                            DriverManager.getConnection(
                                    second.getJdbcUrl(),
                                    second.getUsername(),
                                    second.getPassword());
                    var statement = connection.createStatement()) {
                try (var rows =
                        statement.executeQuery("SELECT to_regclass('public.context_marker')")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                }
                try (var rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM platform.flyway_schema_history WHERE success AND version IS NOT NULL")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isPositive();
                }
            }
        }
    }
}
