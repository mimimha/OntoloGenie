package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** ERD의 온톨로지(V1)·운영(V2)·교차 FK(V3) migration이 PostgreSQL 18에 그대로 적용되는지 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationTests {

    private static final int ONTOLOGY_TABLES = 18;
    private static final int OPERATION_TABLES = 23;

    @Autowired Flyway flyway;

    @Autowired JdbcClient jdbcClient;

    @Test
    void appliesAllMigrations() {
        // 스키마를 만든 기록처럼 Flyway가 스스로 남기는 줄은 버전이 없어 뺀다.
        MigrationInfo[] applied =
                Arrays.stream(flyway.info().applied())
                        .filter(info -> !info.getType().isSynthetic())
                        .toArray(MigrationInfo[]::new);

        assertThat(Arrays.stream(applied).map(info -> info.getVersion().getVersion()))
                .containsExactly("1", "2", "3");
        assertThat(applied).allMatch(info -> info.getState() == MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void createsAllTablesInPlatformSchema() {
        Integer tables =
                jdbcClient
                        .sql(
                                """
                                select count(*) from information_schema.tables
                                where table_schema = 'platform' and table_type = 'BASE TABLE'
                                  and table_name <> 'flyway_schema_history'
                                """)
                        .query(Integer.class)
                        .single();

        assertThat(tables).isEqualTo(ONTOLOGY_TABLES + OPERATION_TABLES);
    }

    @Test
    void connectionUsesPlatformSchema() {
        String schema = jdbcClient.sql("select current_schema()").query(String.class).single();

        assertThat(schema).isEqualTo("platform");
    }

    @Test
    void seedsFixedRoles() {
        assertThat(jdbcClient.sql("select code from role order by id").query(String.class).list())
                .containsExactly("ADMIN", "PLANNER", "MAINTENANCE", "VIEWER");
    }

    @Test
    @Transactional
    void crossDomainForeignKeyRejectsMissingAccount() {
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                insert into object_type (code, name, created_by)
                                                values ('EQUIPMENT', '설비', -1)
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_object_type_created_by");
    }
}
