package com.axcore.platform.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.repository.DataAsOfRepository.SourceTable;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 시점(145)을 구할 원천 표 정보를 실제 PostgreSQL로 읽는다. 원천·표·매핑·객체·수집 기록은 행으로 직접 넣고, 시험마다 되돌려 같은 DB를 쓰는 다른
 * 시험에 남기지 않는다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class DataAsOfRepositoryTests {
    private static final Instant LAST_MESSAGE = Instant.parse("2026-11-11T01:00:00Z");
    private static final Instant HEALTHY_SINCE = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired DataAsOfRepository repository;
    @Autowired JdbcClient jdbc;

    private long sourceId;
    private long typeId;

    @BeforeEach
    void prepare() {
        sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, '기준 시점 시험 원천', 'POSTGRES') returning id
                                """)
                        .param(
                                "code",
                                "AS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name) values ('T-' || gen_random_uuid(), '설비') returning id")
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsCdcStateOfTheSourceAndLoadedLsnOfTheTable() {
        long table = resource("erp.equipment", "CDC", 100L);
        long object = object(mapping(table), "PR-01");
        cdcOffset();
        // 전체 읽기 시작 시각은 전체 비교 수집 표에만 쓰므로, CDC 표의 전체 읽기 기록은 읽지 않는다.
        fullRead(table, "SUCCEEDED", "2026-11-11T10:00:00Z", "2026-11-11T10:30:00Z", null);

        List<SourceTable> tables = repository.tablesOfObjects(List.of(object));

        assertThat(tables)
                .containsExactly(
                        new SourceTable(
                                table, "CDC", 100L, true, true, LAST_MESSAGE, HEALTHY_SINCE, null));
    }

    @Test
    void tellsWhenTheSourceHasNoCdcConsumerYet() {
        long table = resource("erp.equipment", "CDC", null);
        long object = object(mapping(table), "PR-01");

        assertThat(repository.tablesOfObjects(List.of(object)))
                .containsExactly(
                        new SourceTable(table, "CDC", null, true, false, null, null, null));
    }

    @Test
    void tellsWhenAnyObjectOfTheTableComesFromAStoppedMapping() {
        long table = resource("erp.equipment", "CDC", 100L);
        long running = object(mapping(table), "PR-01");
        long stoppedMapping = mapping(table);
        long stopped = object(stoppedMapping, "PR-02");
        jdbc.sql("update object_source_mapping set is_active = false where id = :id")
                .param("id", stoppedMapping)
                .update();

        assertThat(repository.tablesOfObjects(List.of(running)))
                .extracting(SourceTable::mappingActive)
                .containsExactly(true);
        assertThat(repository.tablesOfObjects(List.of(running, stopped)))
                .extracting(SourceTable::mappingActive)
                .containsExactly(false);
    }

    @Test
    void usesStartOfLastSucceededFullReadNotSkippedOrFailedOnes() {
        long table = resource("erp.v_equipment", "FULL_COMPARE", null);
        long object = object(mapping(table), "PR-01");
        // 성공한 전체 읽기는 10:00에 시작해 10:30에 끝났다. 그 뒤 건너뛴 실행과 실패한 실행은 쓰지 않는다.
        fullRead(table, "SUCCEEDED", "2026-11-11T10:00:00Z", "2026-11-11T10:30:00Z", null);
        fullRead(table, "SKIPPED", "2026-11-11T11:00:00Z", "2026-11-11T11:00:01Z", null);
        fullRead(table, "FAILED", "2026-11-11T12:00:00Z", "2026-11-11T12:05:00Z", "원천 접속 실패");

        assertThat(repository.tablesOfObjects(List.of(object)))
                .extracting(SourceTable::lastFullStartedAt)
                .containsExactly(Instant.parse("2026-11-11T10:00:00Z"));
    }

    @Test
    void listsEachSourceTableOnceAndSkipsPlatformAndUnknownObjects() {
        long cdc = resource("erp.equipment", "CDC", 100L);
        long full = resource("erp.v_line", "FULL_COMPARE", null);
        long cdcMapping = mapping(cdc);
        long first = object(cdcMapping, "PR-01");
        long second = object(cdcMapping, "PR-02");
        long line = object(mapping(full), "LINE-A");
        long platform = platformObject();

        List<SourceTable> tables =
                repository.tablesOfObjects(List.of(first, second, line, platform, -1L));

        assertThat(tables)
                .extracting(SourceTable::sourceResourceId)
                .containsExactlyInAnyOrder(cdc, full);
        assertThat(repository.tablesOfObjects(List.of(platform))).isEmpty();
        assertThat(repository.tablesOfObjects(List.of())).isEmpty();
    }

    @Test
    void readsTablesOfBothEndsOfALink() {
        long equipment = resource("erp.equipment", "CDC", 100L);
        long line = resource("erp.v_line", "FULL_COMPARE", null);
        long source = object(mapping(equipment), "PR-01");
        long target = object(mapping(line), "LINE-A");
        long link = link(source, target);

        assertThat(repository.tablesOfLink(link))
                .extracting(SourceTable::sourceResourceId)
                .containsExactlyInAnyOrder(equipment, line);
        assertThat(repository.tablesOfLink(-1L)).isEmpty();
    }

    private long resource(String key, String strategy, Long loadedLsn) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy, loaded_lsn)
                        values (:s, :key, :kind, true, :strategy, :lsn) returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .param("kind", "CDC".equals(strategy) ? "TABLE" : "VIEW")
                .param("strategy", strategy)
                .param("lsn", loadedLsn)
                .query(Long.class)
                .single();
    }

    private long mapping(long resourceId) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:r, :t, '["id"]'::jsonb) returning id
                        """)
                .param("r", resourceId)
                .param("t", typeId)
                .query(Long.class)
                .single();
    }

    private long object(long mappingId, String code) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, source_mapping_id, source_key, display_name)
                        values (:t, :m, jsonb_build_object('id', :code), :code) returning id
                        """)
                .param("t", typeId)
                .param("m", mappingId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long platformObject() {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name)
                        values (:t, '플랫폼에서 만든 객체') returning id
                        """)
                .param("t", typeId)
                .query(Long.class)
                .single();
    }

    private void cdcOffset() {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset,
                                                last_message_at, healthy_since)
                        values (:topic, 0, :s, 0, :lastMessage, :healthySince)
                        """)
                .param("topic", "as-" + sourceId + ".all")
                .param("s", sourceId)
                .param("lastMessage", Timestamp.from(LAST_MESSAGE))
                .param("healthySince", Timestamp.from(HEALTHY_SINCE))
                .update();
    }

    private void fullRead(
            long resourceId, String status, String startedAt, String finishedAt, String error) {
        long runId =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, started_at, finished_at,
                                                      error_message)
                                values (:s, 'SCHEDULED', :runStatus, cast(:started as timestamptz),
                                        cast(:finished as timestamptz), :error)
                                returning id
                                """)
                        .param("s", sourceId)
                        .param("runStatus", "FAILED".equals(status) ? "FAILED" : "SUCCEEDED")
                        .param("started", startedAt)
                        .param("finished", finishedAt)
                        .param("error", error)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode,
                                                       status, started_at, finished_at, error_message)
                        values (:run, :s, :r, 'FULL', :status, cast(:started as timestamptz),
                                cast(:finished as timestamptz), :error)
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .param("status", status)
                .param("started", startedAt)
                .param("finished", finishedAt)
                .param("error", error)
                .update();
    }

    private long link(long source, long target) {
        long linkType =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality,
                                                       description)
                                values ('LINK_' || gen_random_uuid(), '기준 시점 시험 관계', :t, :t, 'MANY_TO_MANY',
                                        '기준 시점 시험')
                                returning id
                                """)
                        .param("t", typeId)
                        .query(Long.class)
                        .single();
        long confirmer =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values ('asof-' || gen_random_uuid(), '기준 시점 시험 관리자', 'x',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .query(Long.class)
                        .single();
        return jdbc.sql(
                        """
                        insert into object_link (link_type_id, source_object_id, target_object_id, origin,
                                                 confirmed_by)
                        values (:type, :source, :target, 'MANUAL', :confirmer) returning id
                        """)
                .param("type", linkType)
                .param("source", source)
                .param("target", target)
                .param("confirmer", confirmer)
                .query(Long.class)
                .single();
    }
}
