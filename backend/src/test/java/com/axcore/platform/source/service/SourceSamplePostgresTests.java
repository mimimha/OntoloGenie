package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.SourceTestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.CredentialPurpose;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "axcore.source-load.zone-id=Asia/Seoul",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@ActiveProfiles("batch")
@Import(SourceTestcontainersConfiguration.class)
class SourceSamplePostgresTests {

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired JdbcClient jdbc;
    @Autowired PostgreSQLContainer postgres;
    @Autowired SourceCredentialService credentials;
    @Autowired SourceSampleRequestService requests;
    @Autowired SourceSampleJob job;
    @Autowired QueuedJobRunner runner;
    @Autowired SourcePreviewQueryService results;
    @Autowired JsonMapper json;

    @Test
    void doesNotStoreTheFourPersonalColumnsEvenWhenTheSourceRoleCanReadThem() {
        jdbc.sql(
                        """
                        create schema erp;
                        create schema mes;
                        create table erp.employee (code text, emp_name text);
                        create table erp.vendor (code text, contact_name text);
                        create table mes.worker (code text, name text);
                        create table mes.maintenance_order (code text, technician text);
                        insert into erp.employee values ('E1', 'excluded-person-value'), ('E2', 'excluded-person-value');
                        insert into erp.vendor values ('V1', 'excluded-person-value'), ('V2', 'excluded-person-value');
                        insert into mes.worker values ('W1', 'excluded-person-value'), ('W2', 'excluded-person-value');
                        insert into mes.maintenance_order values ('M1', 'excluded-person-value'), ('M2', 'excluded-person-value');
                        create role axc_sample_sync login password 'sample-test-only';
                        grant usage on schema erp, mes to axc_sample_sync;
                        grant select on all tables in schema erp, mes to axc_sample_sync;
                        """)
                .update();
        long source =
                jdbc.sql(
                                """
                        insert into data_source (code, name, source_kind, connection_config)
                        values ('SOURCE_SAMPLE_TEST', '원천 표본', 'POSTGRES', cast(:config as jsonb))
                        returning id
                        """)
                        .param(
                                "config",
                                """
                        {"host":"%s","port":%d,"database":"%s"}
                        """
                                        .formatted(
                                                postgres.getHost(),
                                                postgres.getMappedPort(5432),
                                                postgres.getDatabaseName()))
                        .query(Long.class)
                        .single();
        long account =
                jdbc.sql(
                                """
                        insert into account (login_id, display_name, password_hash, role_id, must_change_password)
                        values (:login, '관리자', 'not-used', (select id from role where code = 'ADMIN'), false)
                        returning id
                        """)
                        .param("login", "sample-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        credentials.store(
                source, CredentialPurpose.SYNC, "axc_sample_sync", "sample-test-only", null);
        var admin = new LoginAccount(account, "sample-admin", "ADMIN", false);
        for (var fixture :
                List.of(
                        new Fixture("erp.employee", "emp_name"),
                        new Fixture("erp.vendor", "contact_name"),
                        new Fixture("mes.worker", "name"),
                        new Fixture("mes.maintenance_order", "technician"))) {
            assertThat(
                            jdbc.sql(
                                            """
                    select has_column_privilege('axc_sample_sync', :tableName, :columnName, 'SELECT')
                    """)
                                    .param("tableName", fixture.table())
                                    .param("columnName", fixture.excluded())
                                    .query(Boolean.class)
                                    .single())
                    .isTrue();
            long resource = registerTable(source, fixture);

            var accepted = requests.request(source, resource, 1, admin);
            assertThat(runner.runNext(job)).isTrue();

            var result = results.get(accepted.previewId());
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.result()).containsEntry("columns", List.of("code"));
            var payload = json.valueToTree(result.result());
            assertThat(payload.path("rows").size()).isEqualTo(1);
            assertThat(payload.path("rows").get(0).size()).isEqualTo(1);
            String sampleValue = payload.path("rows").get(0).get(0).asText();
            assertThat(sampleValue).matches("[EVWM][12]");
            assertThat(json.writeValueAsString(payload))
                    .doesNotContain("excluded-person-value", "\"" + fixture.excluded() + "\"");
            assertSampleAudit(accepted.previewId(), sampleValue);
        }
    }

    private void assertSampleAudit(long previewId, String sampleValue) {
        var audit =
                jdbc.sql(
                                """
                select entry_point, input::text, result::text, blocked_reason
                from action_execution
                where event_kind = 'SOURCE_PREVIEW' and target_type = 'source_preview'
                    and target_id = :id
                """)
                        .param("id", previewId)
                        .query()
                        .listOfRows();
        assertThat(audit)
                .extracting(row -> row.get("entry_point"))
                .containsExactlyInAnyOrder("WEB", "BATCH");
        assertThat(audit)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("blocked_reason")).isNull();
                            assertThat(row.get("input").toString())
                                    .doesNotContain(sampleValue, "excluded-person-value");
                            assertThat(row.get("result").toString())
                                    .doesNotContain(sampleValue, "excluded-person-value");
                            if ("BATCH".equals(row.get("entry_point"))) {
                                assertThat(
                                                json.readTree(row.get("result").toString())
                                                        .path("rowCount")
                                                        .asInt())
                                        .isEqualTo(1);
                            }
                        });
    }

    private long registerTable(long source, Fixture fixture) {
        long resource =
                jdbc.sql(
                                """
                insert into source_resource (data_source_id, resource_key, resource_kind, is_readable)
                values (:source, :key, 'TABLE', true)
                returning id
                """)
                        .param("source", source)
                        .param("key", fixture.table())
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                insert into source_column
                    (source_resource_id, column_name, source_data_type, ordinal_position, is_nullable)
                values (:resource, 'code', 'text', 1, true),
                       (:resource, :excluded, 'text', 2, true)
                """)
                .param("resource", resource)
                .param("excluded", fixture.excluded())
                .update();
        return resource;
    }

    private record Fixture(String table, String excluded) {}
}
