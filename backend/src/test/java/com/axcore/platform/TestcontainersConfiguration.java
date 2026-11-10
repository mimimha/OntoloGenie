package com.axcore.platform;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    JdbcConnectionDetails testDatabase() throws SQLException {
        return SharedPostgres.newDatabase();
    }

    // Spring 컨텍스트가 닫혀도 서버는 유지한다. 테스트 JVM 종료 후 Ryuk이 정리한다.
    private static class SharedPostgres {
        private static final PostgreSQLContainer POSTGRES = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer postgres =
                    new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
                            .withDatabaseName("test_template");
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("platform")
                    .load()
                    .migrate();
            return postgres;
        }

        private static synchronized JdbcConnectionDetails newDatabase() throws SQLException {
            String database = "test_" + UUID.randomUUID().toString().replace("-", "");
            String baseUrl =
                    "jdbc:postgresql://"
                            + POSTGRES.getHost()
                            + ":"
                            + POSTGRES.getMappedPort(5432)
                            + "/";
            try (var connection =
                            DriverManager.getConnection(
                                    baseUrl + "postgres",
                                    POSTGRES.getUsername(),
                                    POSTGRES.getPassword());
                    var statement = connection.createStatement()) {
                // 빈 migration 완료 DB만 복제한다. 다른 컨텍스트의 fixture는 공유하지 않는다.
                statement.execute("CREATE DATABASE " + database + " TEMPLATE test_template");
            }
            return new JdbcConnectionDetails() {
                @Override
                public String getJdbcUrl() {
                    return baseUrl + database;
                }

                @Override
                public String getUsername() {
                    return POSTGRES.getUsername();
                }

                @Override
                public String getPassword() {
                    return POSTGRES.getPassword();
                }
            };
        }
    }
}
