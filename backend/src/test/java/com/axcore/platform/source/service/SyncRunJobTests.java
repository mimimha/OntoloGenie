package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.ontology.domain.SourceChange;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.ontology.service.ObjectSyncService;
import com.axcore.platform.source.cdc.CdcStartReference;
import com.axcore.platform.source.domain.CredentialPurpose;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 테이블 전체 읽기(Jira 197)를 실제 PostgreSQL로 확인한다. 같은 컨테이너의 erp_src 스키마를 원천으로 쓰고, 연결 검사 작업으로 카탈로그를 채운 뒤
 * 매핑을 만든다. work_order는 CDC publication에 넣어 CDC 테이블, open_order 뷰는 FULL_COMPARE다.
 */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.source-check.ssl-mode=disable",
            "axcore.source-check.cdc-publication=" + SyncRunJobTests.PUBLICATION,
            "axcore.source-load.zone-id=Asia/Seoul",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE="
        })
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class SyncRunJobTests {

    static final String PUBLICATION = "axc_load_cdc";
    private static final String SYNC_USER = "axc_load_sync";
    private static final String SYNC_PASSWORD = "load-Pa55!";

    // 주기 실행이 테스트가 넣은 작업을 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    // 감시 작업이 시험 데이터에 다시 읽기를 넣지 않게 막는다.
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired QueuedJobRunner queuedJobRunner;
    @Autowired SyncRunJob job;
    @Autowired CdcReloadService reloadService;
    @Autowired SourceConnectionCheckJob connectionCheckJob;
    @Autowired SourceCredentialService credentialService;
    @Autowired ObjectSyncService objectSyncService;
    @Autowired CdcStartReference startReference;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PostgreSQLContainer postgres;

    private TransactionTemplate tx;
    private long accountId;
    private long sourceId;
    private long workOrderId;
    private long typeId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        // 앞 시험이 남긴 대기·실행 중 수집을 지운다. runNext는 가장 먼저 들어온 작업을 가져간다.
        jdbc.sql("delete from sync_run where status in ('QUEUED', 'RUNNING')").update();
        jdbc.sql("drop publication if exists " + PUBLICATION).update();
        jdbc.sql("drop schema if exists erp_src cascade").update();
        jdbc.sql("drop role if exists " + SYNC_USER).update();
        jdbc.sql(
                        """
                        create schema erp_src;
                        create table erp_src.work_order (
                            id bigint primary key, wo_no varchar(20), qty numeric(10, 2), due date,
                            created timestamp, done boolean, secret text);
                        insert into erp_src.work_order values
                            (1, 'WO-0001', 10.50, '2026-11-01', '2026-11-01 09:00:00', false, 's1'),
                            (2, 'WO-0002', 3, '2026-11-02', '2026-11-02 10:30:00', true, 's2'),
                            (3, 'WO-X', null, null, null, null, 's3');
                        create view erp_src.open_order as select wo_no, qty from erp_src.work_order where done is not true
                            union all select null, 0;
                        create role %1$s login password '%2$s';
                        grant usage on schema erp_src to %1$s;
                        grant select (id, wo_no, qty, due, created, done) on erp_src.work_order to %1$s;
                        grant select on erp_src.open_order to %1$s;
                        create publication %3$s for table erp_src.work_order;
                        """
                                .formatted(SYNC_USER, SYNC_PASSWORD, PUBLICATION))
                .update();
        accountId =
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
        sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind, connection_config)
                                values (:code, '테스트 ERP', 'POSTGRES', cast(:config as jsonb))
                                returning id
                                """)
                        .param(
                                "code",
                                "SRC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
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
        refreshCatalog();
        workOrderId = resourceId("erp_src.work_order");

        typeId = newType("작업지시");
        mappingId = newMapping(workOrderId, typeId, "id");
        long woNo = newProperty(typeId, "wo_no", "STRING");
        mapProperty(mappingId, workOrderId, woNo, "wo_no", "[]");
        mapProperty(
                mappingId,
                workOrderId,
                newProperty(typeId, "seq", "INTEGER"),
                "wo_no",
                """
                [{"op": "EXTRACT_NUMBER", "arg": null}]
                """);
        mapProperty(mappingId, workOrderId, newProperty(typeId, "qty", "DECIMAL"), "qty", "[]");
        mapProperty(mappingId, workOrderId, newProperty(typeId, "due", "DATE"), "due", "[]");
        mapProperty(
                mappingId,
                workOrderId,
                newProperty(typeId, "created", "DATETIME"),
                "created",
                "[]");
        mapProperty(mappingId, workOrderId, newProperty(typeId, "done", "BOOLEAN"), "done", "[]");
        jdbc.sql("update object_type set display_property_id = :p where id = :id")
                .param("p", woNo)
                .param("id", typeId)
                .update();
    }

    @Test
    void firstLoadCreatesObjectsAndRecordsGeneration() {
        long runId = queueRun(workOrderId);

        assertThat(queuedJobRunner.runNext(job)).isTrue();

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(objects())
                .containsExactly(
                        Map.of(
                                "key",
                                Map.of("id", "1"),
                                "name",
                                "WO-0001",
                                "status",
                                "ACTIVE",
                                "properties",
                                Map.of(
                                        "wo_no", "WO-0001",
                                        "seq", 1,
                                        "qty", "10.50",
                                        "due", "2026-11-01",
                                        "created", "2026-11-01T09:00+09:00",
                                        "done", false)),
                        Map.of(
                                "key",
                                Map.of("id", "2"),
                                "name",
                                "WO-0002",
                                "status",
                                "ACTIVE",
                                "properties",
                                Map.of(
                                        "wo_no", "WO-0002",
                                        "seq", 2,
                                        "qty", "3.00",
                                        "due", "2026-11-02",
                                        "created", "2026-11-02T10:30+09:00",
                                        "done", true)),
                        // 숫자가 없는 WO-X는 seq만 빠지고, 비어 있는 값은 null로 남는다.
                        Map.of(
                                "key",
                                Map.of("id", "3"),
                                "name",
                                "WO-X",
                                "status",
                                "ACTIVE",
                                "properties",
                                nullable(
                                        "wo_no", "WO-X", "qty", null, "due", null, "created", null,
                                        "done", null)));
        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from object_instance
                                        where source_mapping_id = :m and loaded_gen = :run
                                          and loaded_snapshot is not null and last_sync_run_id = :run
                                        """)
                                .param("m", mappingId)
                                .param("run", runId)
                                .query(Long.class)
                                .single())
                .isEqualTo(3);

        Map<String, Object> resource = runResource(runId, workOrderId);
        assertThat(resource)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("rows_read", 3)
                .containsEntry("rows_upserted", 3)
                .containsEntry("rows_deleted", 0);
        assertThat(resource.get("to_lsn")).isNotNull();
        assertThat(resource.get("started_at")).isNotNull();
        Map<String, Object> loaded = loadState(workOrderId);
        assertThat(loaded).containsEntry("full_load_done_gen", runId);
        assertThat(loaded.get("loaded_lsn")).isEqualTo(resource.get("to_lsn"));

        Map<String, Object> audit =
                jdbc.sql(
                                """
                                select status, result::text as result from action_execution
                                where target_type = 'sync_run' and target_id = :id and actor_type = 'SYSTEM'
                                """)
                        .param("id", runId)
                        .query()
                        .singleRow();
        assertThat(audit).containsEntry("status", "SUCCEEDED");
        assertThat(json.readValue((String) audit.get("result"), Map.class))
                .containsEntry("rowsRead", 3)
                .containsEntry("objects", 3)
                .containsEntry("failedValues", 1);
        assertThat((String) audit.get("result")).doesNotContain("WO-");
    }

    @Test
    void reloadWatcherQueuesFirstLoadThatJobLoads() {
        reloadService.queueDueReloads();
        long runId =
                jdbc.sql(
                                """
                                select sync_run_id from sync_run_resource
                                where source_resource_id = :r and status = 'QUEUED'
                                """)
                        .param("r", workOrderId)
                        .query(Long.class)
                        .single();
        // 같은 DB에 앞 시험이 남긴 원천 테이블이 함께 들어갈 수 있다. 이 시험의 실행만 남긴다.
        jdbc.sql("delete from sync_run where status = 'QUEUED' and id <> :id")
                .param("id", runId)
                .update();

        assertThat(queuedJobRunner.runNext(job)).isTrue();

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.sql("select trigger_kind from sync_run where id = :id")
                                .param("id", runId)
                                .query(String.class)
                                .single())
                .isEqualTo("RECOVERY");
        assertThat(loadState(workOrderId))
                .containsEntry("full_load_done_gen", runId)
                .doesNotContainEntry("loaded_lsn", null);
        assertThat(objects()).isNotEmpty();

        // 다시 읽기 표시가 풀렸으므로 더 넣지 않는다.
        reloadService.queueDueReloads();
        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from sync_run_resource
                                        where source_resource_id = :r and status = 'QUEUED'
                                        """)
                                .param("r", workOrderId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void reloadKeepsIdsTouchesOnlyChangedRowsAndMarksMissing() {
        long first = queueRun(workOrderId);
        queuedJobRunner.runNext(job);
        Map<String, Object> before = objectRows();
        jdbc.sql(
                        """
                        update erp_src.work_order set qty = 11 where id = 1;
                        delete from erp_src.work_order where id = 3;
                        """)
                .update();

        long second = queueRun(workOrderId);
        queuedJobRunner.runNext(job);

        Map<String, Object> after = objectRows();
        assertThat(after.keySet()).isEqualTo(before.keySet());
        assertThat(row(after, "1"))
                .containsEntry("last_sync_run_id", second)
                .containsEntry("status", "ACTIVE");
        // 값이 그대로인 행은 내용 시각·실행을 바꾸지 않고 적재 세대만 올린다.
        assertThat(row(after, "2"))
                .containsEntry("last_sync_run_id", first)
                .containsEntry("last_synced_at", row(before, "2").get("last_synced_at"))
                .containsEntry("loaded_gen", second);
        assertThat(row(after, "3")).containsEntry("status", "DELETED_AT_SOURCE");
        assertThat(runResource(second, workOrderId))
                .containsEntry("rows_read", 2)
                .containsEntry("rows_deleted", 1);
        assertThat(loadState(workOrderId)).containsEntry("full_load_done_gen", second);
    }

    @Test
    void keepsCdcChangesCommittedAfterSnapshot() {
        queueRun(workOrderId);
        queuedJobRunner.runNext(job);
        long runId = queueRun(workOrderId);
        startRun(runId);
        SyncRunJob.Outcome outcome = job.run(runId);

        // 스냅샷 뒤에 원천에서 1번을 고치고 3번을 지웠고, CDC 소비자가 먼저 반영했다.
        long cdcRun = cdcRun();
        long updateTxid = sourceWrite("update erp_src.work_order set qty = 99 where id = 1");
        long deleteTxid = sourceWrite("delete from erp_src.work_order where id = 3");
        tx.executeWithoutResult(
                status -> {
                    objectSyncService.applyChange(
                            new SyncedObject(
                                    workOrderId,
                                    mappingId,
                                    typeId,
                                    Map.of("id", "1"),
                                    "WO-0001",
                                    Map.of("wo_no", "WO-0001", "qty", "99.00")),
                            new SourceChange(updateTxid, 1_000_000_000L, cdcRun));
                    objectSyncService.applyDelete(
                            workOrderId,
                            Map.of("id", "3"),
                            new SourceChange(deleteTxid, 1_000_000_100L, cdcRun));
                });

        tx.executeWithoutResult(status -> job.complete(runId, outcome));

        Map<String, Object> rows = objectRows();
        assertThat(properties(row(rows, "1"))).containsEntry("qty", "99.00");
        assertThat(row(rows, "3")).containsEntry("status", "DELETED_AT_SOURCE");
        assertThat(row(rows, "2")).containsEntry("loaded_gen", runId);
        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void refusesToStoreWhenMappingChangedDuringRead() {
        long runId = queueRun(workOrderId);
        startRun(runId);
        SyncRunJob.Outcome outcome = job.run(runId);
        jdbc.sql(
                        """
                        update property_source_mapping
                        set transform_steps = '[{"op": "TRIM", "arg": null}]', row_version = row_version + 1
                        where source_mapping_id = :m and transform_steps = '[]'::jsonb
                          and property_definition_id = (select id from property_definition
                                                        where object_type_id = :t and code = 'wo_no')
                        """)
                .param("m", mappingId)
                .param("t", typeId)
                .update();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> job.complete(runId, outcome)))
                .isInstanceOf(QueuedJobFailure.class)
                .hasMessageContaining("erp_src.work_order");
        assertThat(objects()).isEmpty();
    }

    @Test
    void lateCompletionOfRunAlreadyClosedDoesNotWrite() {
        long runId = queueRun(workOrderId);
        startRun(runId);
        SyncRunJob.Outcome outcome = job.run(runId);
        // 오래 멈춘 실행을 정리하는 쪽이 먼저 FAILED로 바꿨다.
        jdbc.sql(
                        """
                        update sync_run set status = 'FAILED', finished_at = now(), error_message = '시간 초과'
                        where id = :id
                        """)
                .param("id", runId)
                .update();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> job.complete(runId, outcome)))
                .isInstanceOf(QueuedJobFailure.class);
        assertThat(objects()).isEmpty();
        assertThat(loadState(workOrderId).get("full_load_done_gen")).isNull();
    }

    @Test
    void failureEndsTablesSoTheTableCanBeQueuedAgain() {
        jdbc.sql(
                        "revoke select (id, wo_no, qty, due, created, done) on erp_src.work_order from "
                                + SYNC_USER)
                .update();
        long runId = queueRun(workOrderId);

        queuedJobRunner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("FAILED");
        Map<String, Object> resource = runResource(runId, workOrderId);
        assertThat(resource).containsEntry("status", "FAILED");
        assertThat((String) resource.get("error_message")).contains("권한");
        assertThat(loadState(workOrderId).get("full_load_done_gen")).isNull();
        // 대기·실행 중 유일 조건이 풀려 같은 테이블을 다시 넣을 수 있다.
        queueRun(workOrderId);
    }

    @Test
    void loadsViewAsFullCompareAndSkipsRowsWithoutKey() {
        long viewId = resourceId("erp_src.open_order");
        long viewType = newType("열린 주문");
        long viewMapping = newMapping(viewId, viewType, "wo_no");
        mapProperty(viewMapping, viewId, newProperty(viewType, "qty", "DECIMAL"), "qty", "[]");
        long runId = queueRun(viewId);

        queuedJobRunner.runNext(job);

        assertThat(
                        jdbc.sql(
                                        """
                                        select source_key::text from object_instance
                                        where source_mapping_id = :m order by id
                                        """)
                                .param("m", viewMapping)
                                .query(String.class)
                                .list())
                .containsExactly("{\"wo_no\": \"WO-0001\"}", "{\"wo_no\": \"WO-X\"}");
        Map<String, Object> resource = runResource(runId, viewId);
        assertThat(resource).containsEntry("status", "SUCCEEDED").containsEntry("rows_read", 3);
        assertThat(resource.get("to_lsn")).isNull();
        assertThat(loadState(viewId).get("full_load_done_gen")).isNull();
    }

    @Test
    void numericNaNDropsOnlyThatPropertyInsteadOfFailingTheRun() {
        jdbc.sql("update erp_src.work_order set qty = 'NaN' where id = 3").update();
        long runId = queueRun(workOrderId);

        queuedJobRunner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        Map<String, Object> rows = objectRows();
        assertThat(rows).hasSize(3);
        assertThat(properties(row(rows, "3"))).doesNotContainKey("qty").containsKey("due");
        // seq(숫자 없는 WO-X)와 NaN qty 두 값을 뺐다.
        assertThat(auditResult(runId)).containsEntry("failedValues", 2);
    }

    @Test
    void timestampKeyIsUtcAndLongDisplayNameIsCut() {
        jdbc.sql(
                        """
                        create table erp_src.note (at timestamptz primary key, title text);
                        grant select on erp_src.note to %s;
                        """
                                .formatted(SYNC_USER))
                .update();
        jdbc.sql("insert into erp_src.note values ('2026-11-01 09:00:00+09', :title)")
                .param("title", "가".repeat(299) + "😀😀")
                .update();
        refreshCatalog();
        long noteId = resourceId("erp_src.note");
        long noteType = newType("메모");
        long noteMapping = newMapping(noteId, noteType, "at");
        long title = newProperty(noteType, "title", "STRING");
        mapProperty(noteMapping, noteId, title, "title", "[]");
        jdbc.sql("update object_type set display_property_id = :p where id = :id")
                .param("p", title)
                .param("id", noteType)
                .update();
        long runId = queueRun(noteId);

        queuedJobRunner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        Map<String, Object> note =
                jdbc.sql(
                                """
                                select source_key::text as key, display_name from object_instance
                                where source_mapping_id = :m
                                """)
                        .param("m", noteMapping)
                        .query()
                        .singleRow();
        // 세션 시간대와 상관없이 UTC 순간으로 남는다(CDC 소비자도 같은 문자열을 만든다).
        assertThat(note.get("key")).isEqualTo("{\"at\": \"2026-11-01T00:00:00Z\"}");
        assertThat(note.get("display_name")).isEqualTo("가".repeat(299) + "😀");
    }

    @Test
    void duplicateKeysInViewAreCountedInsteadOfOverwritten() {
        jdbc.sql(
                        """
                        create view erp_src.dup_order as
                            select wo_no, qty from erp_src.work_order where id in (1, 2)
                            union all select 'WO-0001', 99;
                        grant select on erp_src.dup_order to %s;
                        """
                                .formatted(SYNC_USER))
                .update();
        refreshCatalog();
        long viewId = resourceId("erp_src.dup_order");
        long viewType = newType("중복 주문");
        long viewMapping = newMapping(viewId, viewType, "wo_no");
        mapProperty(viewMapping, viewId, newProperty(viewType, "qty", "DECIMAL"), "qty", "[]");
        long runId = queueRun(viewId);

        queuedJobRunner.runNext(job);

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.sql(
                                        """
                                        select source_key->>'wo_no' from object_instance
                                        where source_mapping_id = :m order by 1
                                        """)
                                .param("m", viewMapping)
                                .query(String.class)
                                .list())
                .containsExactly("WO-0001", "WO-0002");
        assertThat(runResource(runId, viewId))
                .containsEntry("rows_read", 3)
                .containsEntry("rows_upserted", 2);
        assertThat(auditResult(runId)).containsEntry("duplicateKeyRows", 1);
    }

    @Test
    void catalogRecheckClearsLoadGenerationWhenTableLeavesCdc() {
        queueRun(workOrderId);
        queuedJobRunner.runNext(job);
        jdbc.sql("alter publication " + PUBLICATION + " drop table erp_src.work_order").update();

        refreshCatalog();

        Map<String, Object> state =
                jdbc.sql(
                                """
                                select sync_strategy, full_load_done_gen, loaded_lsn
                                from source_resource where id = :id
                                """)
                        .param("id", workOrderId)
                        .query()
                        .singleRow();
        assertThat(state.get("sync_strategy")).isEqualTo("FULL_COMPARE");
        assertThat(state.get("full_load_done_gen")).isNull();
        assertThat(state.get("loaded_lsn")).isNull();
    }

    private void refreshCatalog() {
        jdbc.sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by, requested_at)
                        values (:sourceId, :accountId, clock_timestamp())
                        """)
                .param("sourceId", sourceId)
                .param("accountId", accountId)
                .update();
        assertThat(queuedJobRunner.runNext(connectionCheckJob)).isTrue();
    }

    /**
     * 전체 읽기 저장이 끝나면 읽은 매핑의 객체 유형으로 연결을 다시 계산한다(198-7). 열린 주문(뷰).wo_no = 작업지시.wo_no 규칙에서, 작업지시만 읽었을
     * 때는 연결이 없고 열린 주문을 읽은 실행이 연결을 완성한다(늦게 들어온 쪽이 양쪽으로 다시 계산).
     */
    @Test
    void fullLoadRecomputesLinksOfLoadedTypes() {
        long viewId = resourceId("erp_src.open_order");
        long viewType = newType("열린 주문");
        long viewMapping = newMapping(viewId, viewType, "wo_no");
        long viewWoNo = newProperty(viewType, "wo_no", "STRING");
        mapProperty(viewMapping, viewId, viewWoNo, "wo_no", "[]");
        long woNo =
                jdbc.sql(
                                "select id from property_definition where object_type_id = :t and code = 'wo_no'")
                        .param("t", typeId)
                        .query(Long.class)
                        .single();
        long linkType =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values ('LT-' || gen_random_uuid(), '열린 주문 작업지시', :s, :t, 'MANY_TO_ONE', '198-7 시험')
                                returning id
                                """)
                        .param("s", viewType)
                        .param("t", typeId)
                        .query(Long.class)
                        .single();
        // 비교 조건은 규칙을 만든 트랜잭션 안에서만 넣을 수 있다(DB 트리거)
        tx.executeWithoutResult(
                status -> {
                    long rule =
                            jdbc.sql(
                                            """
                                            insert into link_mapping (link_type_id, code, revision)
                                            values (:t, 'R-' || gen_random_uuid(), 1) returning id
                                            """)
                                    .param("t", linkType)
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into link_mapping_condition
                                        (link_mapping_id, source_property_id, target_property_id, sequence_no)
                                    values (:r, :s, :t, 1)
                                    """)
                            .param("r", rule)
                            .param("s", viewWoNo)
                            .param("t", woNo)
                            .update();
                });

        queueRun(workOrderId);
        assertThat(queuedJobRunner.runNext(job)).isTrue();
        assertThat(activeLinks(linkType)).isEmpty();

        long viewRun = queueRun(viewId);
        assertThat(queuedJobRunner.runNext(job)).isTrue();
        assertThat(runStatus(viewRun)).isEqualTo("SUCCEEDED");
        // 열린 주문 WO-0001 → 작업지시 1, WO-X → 작업지시 3 (WO-0002는 끝나서 뷰에 없다)
        assertThat(activeLinks(linkType)).containsExactlyInAnyOrder("WO-0001->1", "WO-X->3");
    }

    private List<String> activeLinks(long linkType) {
        return jdbc.sql(
                        """
                        select (s.source_key ->> 'wo_no') || '->' || (t.source_key ->> 'id')
                        from object_link l
                        join object_instance s on s.id = l.source_object_id
                        join object_instance t on t.id = l.target_object_id
                        where l.link_type_id = :t and l.status = 'ACTIVE'
                        """)
                .param("t", linkType)
                .query(String.class)
                .list();
    }

    /** CDC를 처음 소비할 때의 epoch 기준값은 원천의 xmax를 SYNC 계정으로 읽는다(198-6). 원천에 쓰지 않는다. */
    @Test
    void startReferenceReadsSourceXmaxWithSyncAccount() {
        long before =
                Long.parseLong(
                        jdbc.sql("select pg_snapshot_xmax(pg_current_snapshot())::text")
                                .query(String.class)
                                .single());

        long xmax = startReference.read(sourceId);

        // 시험 원천은 같은 컨테이너다. 읽는 사이 다른 트랜잭션이 번호를 받을 수 있어 범위로 본다
        assertThat(xmax).isBetween(before, before + 1_000);
    }

    private long resourceId(String key) {
        return jdbc.sql(
                        "select id from source_resource where data_source_id = :id and resource_key = :key")
                .param("id", sourceId)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private long newType(String name) {
        return jdbc.sql(
                        "insert into object_type (code, name) values ('T-' || gen_random_uuid(), :name) returning id")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long newProperty(long objectTypeId, String code, String dataType) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:t, :code, :code, :type) returning id
                        """)
                .param("t", objectTypeId)
                .param("code", code)
                .param("type", dataType)
                .query(Long.class)
                .single();
    }

    private long newMapping(long resourceId, long objectTypeId, String keyColumn) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:r, :t, jsonb_build_array(:key)) returning id
                        """)
                .param("r", resourceId)
                .param("t", objectTypeId)
                .param("key", keyColumn)
                .query(Long.class)
                .single();
    }

    private void mapProperty(
            long mapping, long resourceId, long propertyId, String column, String steps) {
        jdbc.sql(
                        """
                        insert into property_source_mapping (source_mapping_id, source_resource_id,
                                                             property_definition_id, source_column_id,
                                                             transform_steps)
                        values (:m, :r, :p,
                                (select id from source_column where source_resource_id = :r and column_name = :c),
                                cast(:steps as jsonb))
                        """)
                .param("m", mapping)
                .param("r", resourceId)
                .param("p", propertyId)
                .param("c", column)
                .param("steps", steps)
                .update();
    }

    private long queueRun(long resourceId) {
        long runId =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, requested_by, requested_at)
                                values (:s, 'MANUAL', :a, clock_timestamp()) returning id
                                """)
                        .param("s", sourceId)
                        .param("a", accountId)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode)
                        values (:run, :s, :r, 'FULL')
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .update();
        return runId;
    }

    /** 배치가 가져간 것처럼 RUNNING으로 바꾼다. run·complete를 직접 부르는 시험에서 쓴다. */
    private void startRun(long runId) {
        jdbc.sql("update sync_run set status = 'RUNNING', started_at = now() where id = :id")
                .param("id", runId)
                .update();
    }

    private long cdcRun() {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, status, started_at, finished_at)
                        values (:s, 'CDC', 'SUCCEEDED', now(), now()) returning id
                        """)
                .param("s", sourceId)
                .query(Long.class)
                .single();
    }

    /** 원천에 쓰고 그 트랜잭션 번호를 돌려준다(CDC 이벤트의 txId를 epoch 복원한 값에 해당). */
    private long sourceWrite(String sql) {
        return tx.execute(
                status -> {
                    jdbc.sql(sql).update();
                    return jdbc.sql("select pg_current_xact_id()::text::bigint")
                            .query(Long.class)
                            .single();
                });
    }

    private String runStatus(long runId) {
        return jdbc.sql("select status from sync_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private Map<String, Object> runResource(long runId, long resourceId) {
        return jdbc.sql(
                        """
                        select status, rows_read, rows_upserted, rows_deleted, to_lsn, started_at,
                               error_message
                        from sync_run_resource where sync_run_id = :run and source_resource_id = :r
                        """)
                .param("run", runId)
                .param("r", resourceId)
                .query()
                .singleRow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditResult(long runId) {
        String result =
                jdbc.sql(
                                """
                                select result::text from action_execution
                                where target_type = 'sync_run' and target_id = :id and actor_type = 'SYSTEM'
                                """)
                        .param("id", runId)
                        .query(String.class)
                        .single();
        return json.readValue(result, Map.class);
    }

    private Map<String, Object> loadState(long resourceId) {
        return jdbc.sql("select full_load_done_gen, loaded_lsn from source_resource where id = :id")
                .param("id", resourceId)
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> objects() {
        return jdbc.sql(
                        """
                        select source_key::text as key, display_name, status, properties::text as properties
                        from object_instance where source_mapping_id = :m order by source_key->>'id'
                        """)
                .param("m", mappingId)
                .query(
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "key", json.readValue(rs.getString("key"), Map.class),
                                        "name", rs.getString("display_name"),
                                        "status", rs.getString("status"),
                                        "properties",
                                                json.readValue(
                                                        rs.getString("properties"), Map.class)))
                .list();
    }

    /** 원천 id → 객체 행. */
    private Map<String, Object> objectRows() {
        Map<String, Object> rows = new TreeMap<>();
        jdbc.sql(
                        """
                        select source_key->>'id' as key, id, status, last_sync_run_id, loaded_gen,
                               last_synced_at, properties::text as properties
                        from object_instance where source_mapping_id = :m
                        """)
                .param("m", mappingId)
                .query()
                .listOfRows()
                .forEach(row -> rows.put((String) row.get("key"), row));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> rows, String key) {
        return (Map<String, Object>) rows.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> row) {
        return json.readValue((String) row.get("properties"), Map.class);
    }

    private static Map<String, Object> nullable(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
