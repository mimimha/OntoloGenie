package com.axcore.platform.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.SourceTestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.mapping.dto.MappingPreviewAccepted;
import com.axcore.platform.mapping.dto.MappingPreviewRequest;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.Draft;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.PropertyDraft;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.service.CdcReloadJob;
import com.axcore.platform.source.service.SourceConnectionCheckJob;
import com.axcore.platform.source.service.SourceCredentialService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매핑 미리보기 작업(040·102, S15P31C106-185)을 실제 PostgreSQL로 확인한다. 같은 컨테이너의 prev_src 스키마를 원천으로 쓰고, 연결 검사
 * 작업으로 카탈로그를 채운 뒤 초안을 미리 본다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "axcore.source-load.zone-id=Asia/Seoul",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@ActiveProfiles("batch")
@Import(SourceTestcontainersConfiguration.class)
@SuppressWarnings("unchecked")
class MappingPreviewJobTests {

    private static final String SYNC_USER = "axc_prev_sync";
    private static final String SYNC_PASSWORD = "prev-Pa55!";

    // 주기 실행과 감시 작업이 시험이 넣은 작업을 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired QueuedJobRunner queuedJobRunner;
    @Autowired MappingPreviewJob job;
    @Autowired MappingPreviewService previewService;
    @Autowired SourcePreviewCleanupJob cleanupJob;
    @Autowired SourceConnectionCheckJob connectionCheckJob;
    @Autowired SourceCredentialService credentialService;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PostgreSQLContainer postgres;

    private LoginAccount admin;
    private long sourceId;
    private long resourceId;
    private long typeId;
    private long woNo;
    private long seq;
    private long due;

    @BeforeEach
    void prepare() {
        // 앞 시험이 남긴 대기 중 미리보기를 지운다. runNext는 가장 먼저 들어온 작업을 가져간다.
        jdbc.sql("delete from source_preview where status in ('QUEUED', 'RUNNING')").update();
        jdbc.sql("drop schema if exists prev_src cascade").update();
        jdbc.sql("drop role if exists " + SYNC_USER).update();
        jdbc.sql(
                        """
                        create schema prev_src;
                        create table prev_src.work_order (id bigint primary key, wo_no varchar(20), due text);
                        insert into prev_src.work_order values
                            (1, 'WO-0001', '2026-11-01'),
                            (2, 'WO-0002', 'not a date'),
                            (3, 'X-9', '2026-11-03');
                        create role %1$s login password '%2$s';
                        grant usage on schema prev_src to %1$s;
                        grant select on prev_src.work_order to %1$s;
                        """
                                .formatted(SYNC_USER, SYNC_PASSWORD))
                .update();
        long accountId =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '관리자', 'not-used',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("loginId", "admin-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        admin = new LoginAccount(accountId, "preview-admin", "ADMIN", false);
        sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind, connection_config)
                                values (:code, '미리보기 ERP', 'POSTGRES', cast(:config as jsonb))
                                returning id
                                """)
                        .param(
                                "code",
                                "PRV_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .param(
                                "config",
                                """
                                {"host": "%s", "port": %d, "database": "%s"}
                                """
                                        .formatted(
                                                postgres.getHost(),
                                                postgres.getMappedPort(5432),
                                                postgres.getDatabaseName()))
                        .query(Long.class)
                        .single();
        credentialService.store(sourceId, CredentialPurpose.SYNC, SYNC_USER, SYNC_PASSWORD, null);
        jdbc.sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by, requested_at)
                        values (:sourceId, :accountId, clock_timestamp())
                        """)
                .param("sourceId", sourceId)
                .param("accountId", accountId)
                .update();
        assertThat(queuedJobRunner.runNext(connectionCheckJob)).isTrue();
        resourceId =
                jdbc.sql(
                                "select id from source_resource where data_source_id = :s and resource_key = 'prev_src.work_order'")
                        .param("s", sourceId)
                        .query(Long.class)
                        .single();
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name) values ('PRV-' || gen_random_uuid(), '작업지시') returning id")
                        .query(Long.class)
                        .single();
        woNo = property("wo_no", "STRING");
        seq = property("seq", "INTEGER");
        due = property("due", "DATE");
        jdbc.sql("update object_type set display_property_id = :p where id = :id")
                .param("p", woNo)
                .param("id", typeId)
                .update();
    }

    @Test
    void previewsDraftWithRowErrorsWithoutSavingObjects() {
        MappingPreviewAccepted accepted =
                previewService.request(
                        resourceId,
                        new MappingPreviewRequest(
                                new Draft(
                                        typeId,
                                        List.of("id"),
                                        null,
                                        List.of(
                                                draft(woNo, "wo_no", List.of()),
                                                draft(
                                                        seq,
                                                        "wo_no",
                                                        List.of(
                                                                new TransformStepPayload(
                                                                        "STRIP_PREFIX", "WO-"),
                                                                new TransformStepPayload(
                                                                        "TO_INTEGER", null))),
                                                draft(due, "due", List.of()))),
                                null),
                        admin);

        assertThat(queuedJobRunner.runNext(job)).isTrue();

        Map<String, Object> row = previewRow(accepted.previewId());
        assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("error_message", null);
        Map<String, Object> result = json.readValue((String) row.get("result"), Map.class);
        List<Map<String, Object>> objects = (List<Map<String, Object>>) result.get("objects");
        assertThat(objects).hasSize(3);
        assertThat(objects.get(0))
                .containsEntry("sourceKey", Map.of("id", "1"))
                .containsEntry("displayName", "WO-0001")
                .containsEntry(
                        "properties", Map.of("wo_no", "WO-0001", "seq", 1, "due", "2026-11-01"));
        // 2: 날짜가 아니라 due만 빠진다. 3: 접두어가 없어 TO_INTEGER에서 실패해 seq만 빠진다
        assertThat((Map<String, Object>) objects.get(1).get("properties"))
                .containsOnlyKeys("wo_no", "seq");
        assertThat((Map<String, Object>) objects.get(2).get("properties"))
                .containsOnlyKeys("wo_no", "due");
        List<Map<String, Object>> rowErrors = (List<Map<String, Object>>) result.get("rowErrors");
        assertThat(rowErrors).hasSize(2);
        assertThat(rowErrors.get(0))
                .containsEntry("rowIndex", 1)
                .containsEntry("column", "due")
                .containsEntry("transform", null);
        assertThat((String) rowErrors.get(0).get("message")).contains("DATE");
        assertThat(rowErrors.get(1))
                .containsEntry("rowIndex", 2)
                .containsEntry("column", "wo_no")
                .containsEntry("transform", "TO_INTEGER");
        // 업무 객체는 만들지 않는다
        assertThat(
                        jdbc.sql("select count(*) from object_instance where object_type_id = :t")
                                .param("t", typeId)
                                .query(Long.class)
                                .single())
                .isZero();
        // 결과 줄 감사 기록에는 건수만 남는다
        String audit =
                jdbc.sql(
                                """
                                select result::text from action_execution
                                where target_type = 'source_preview' and target_id = :id and actor_type = 'SYSTEM'
                                """)
                        .param("id", accepted.previewId())
                        .query(String.class)
                        .single();
        assertThat(audit).contains("\"objects\": 3").doesNotContain("WO-");
    }

    @Test
    void readsOnlyRowLimitRowsAndSkipsRowsWithoutKey() {
        jdbc.sql("alter table prev_src.work_order drop constraint work_order_pkey").update();
        jdbc.sql("alter table prev_src.work_order alter column id drop not null").update();
        jdbc.sql("insert into prev_src.work_order values (null, 'WO-NULL', null)").update();
        MappingPreviewAccepted accepted =
                previewService.request(
                        resourceId,
                        new MappingPreviewRequest(
                                new Draft(
                                        typeId,
                                        List.of("id"),
                                        Map.of(),
                                        List.of(draft(woNo, "wo_no", List.of()))),
                                2),
                        admin);

        assertThat(queuedJobRunner.runNext(job)).isTrue();

        Map<String, Object> result =
                json.readValue((String) previewRow(accepted.previewId()).get("result"), Map.class);
        List<Map<String, Object>> objects = (List<Map<String, Object>>) result.get("objects");
        List<Map<String, Object>> rowErrors = (List<Map<String, Object>>) result.get("rowErrors");
        assertThat(objects.size() + rowErrors.size()).isEqualTo(2);
        assertThat(rowErrors)
                .allSatisfy(
                        error ->
                                assertThat(error)
                                        .containsEntry("column", "id")
                                        .containsEntry("transform", null));
    }

    @Test
    void failsWhenSyncAccountCannotReadTable() {
        MappingPreviewAccepted accepted =
                previewService.request(
                        resourceId,
                        new MappingPreviewRequest(
                                new Draft(
                                        typeId,
                                        List.of("id"),
                                        null,
                                        List.of(draft(woNo, "wo_no", List.of()))),
                                null),
                        admin);
        jdbc.sql("revoke select on prev_src.work_order from " + SYNC_USER).update();

        queuedJobRunner.runNext(job);

        Map<String, Object> row = previewRow(accepted.previewId());
        assertThat(row).containsEntry("status", "FAILED").containsEntry("result", null);
        assertThat((String) row.get("error_message")).contains("권한");
    }

    @Test
    void cleanupDeletesExpiredPreviews() {
        MappingPreviewAccepted accepted =
                previewService.request(
                        resourceId,
                        new MappingPreviewRequest(
                                new Draft(typeId, List.of("id"), null, List.of()), null),
                        admin);
        long kept =
                previewService
                        .request(
                                resourceId,
                                new MappingPreviewRequest(
                                        new Draft(typeId, List.of("id"), null, List.of()), 5),
                                admin)
                        .previewId();
        jdbc.sql(
                        """
                        update source_preview set requested_at = now() - interval '2 hours',
                                                  expires_at = now() - interval '1 hour'
                        where id = :id
                        """)
                .param("id", accepted.previewId())
                .update();

        cleanupJob.run();

        assertThat(
                        jdbc.sql("select id from source_preview where id in (:ids)")
                                .param("ids", List.of(accepted.previewId(), kept))
                                .query(Long.class)
                                .list())
                .containsExactly(kept);
    }

    private PropertyDraft draft(long propertyId, String column, List<TransformStepPayload> steps) {
        long columnId =
                jdbc.sql(
                                "select id from source_column where source_resource_id = :r and column_name = :c")
                        .param("r", resourceId)
                        .param("c", column)
                        .query(Long.class)
                        .single();
        return new PropertyDraft(propertyId, columnId, null, steps);
    }

    private long property(String code, String dataType) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:t, :code, :code, :type) returning id
                        """)
                .param("t", typeId)
                .param("code", code)
                .param("type", dataType)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> previewRow(long id) {
        return jdbc.sql(
                        "select status, result::text as result, error_message from source_preview where id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }
}
