package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.ontology.domain.FullLoad;
import com.axcore.platform.ontology.domain.SourceChange;
import com.axcore.platform.ontology.domain.SyncedObject;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원천 객체 저장(Jira 180)의 판단 규칙을 PostgreSQL에서 확인한다. 스냅샷과 트랜잭션 번호는 값만으로 판단되므로 직접 정한다: 스냅샷 "100:100:"에서는
 * 100보다 작은 번호가 보이고 그 이상은 안 보인다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class ObjectSyncServiceTests {

    private static final String S100 = "100:100:";
    private static final String S200 = "200:200:";

    @Autowired ObjectSyncService service;

    @Autowired JdbcClient jdbc;

    private long resourceId;
    private long mappingId;
    private long typeId;
    private long cdcRun;

    @BeforeEach
    void setUp() {
        long sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values ('ERP-' || gen_random_uuid(), 'ERP', 'POSTGRES') returning id
                                """)
                        .query(Long.class)
                        .single();
        resourceId =
                jdbc.sql(
                                """
                                insert into source_resource
                                    (data_source_id, resource_key, resource_kind, sync_strategy)
                                values (:source, 'erp.work_order', 'TABLE', 'CDC') returning id
                                """)
                        .param("source", sourceId)
                        .query(Long.class)
                        .single();
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name) values ('WO-' || gen_random_uuid(), '작업지시') returning id")
                        .query(Long.class)
                        .single();
        mappingId =
                jdbc.sql(
                                """
                                insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                                values (:resource, :type, '["id"]') returning id
                                """)
                        .param("resource", resourceId)
                        .param("type", typeId)
                        .query(Long.class)
                        .single();
        cdcRun = newRun("CDC");
    }

    @Test
    void sameSourceRowKeepsObjectId() {
        long first = service.applyChange(row(1, 10), change(150, 10)).orElseThrow();
        long second = service.applyChange(row(1, 11), change(151, 20)).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(qty(first)).isEqualTo("11");
    }

    @Test
    void changeAlreadyInLoadIsSkipped() {
        long id = service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY"))).orElseThrow();

        // 번호 50은 스냅샷 100에서 보인다: 적재값에 이미 들어 있다(시험 2·4에서 역행을 만든 경우)
        Optional<Long> skipped = service.applyChange(row(1, 9), change(50, 5));

        assertThat(skipped).isEmpty();
        assertThat(qty(id)).isEqualTo("10");
    }

    @Test
    void changeAfterLoadIsApplied() {
        long id = service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY"))).orElseThrow();

        assertThat(service.applyChange(row(1, 12), change(150, 5))).contains(id);
        assertThat(qty(id)).isEqualTo("12");
    }

    @Test
    void replayedChangeIsSkipped() {
        long id = service.applyChange(row(1, 10), change(150, 20)).orElseThrow();

        assertThat(service.applyChange(row(1, 9), change(149, 10))).isEmpty();
        assertThat(qty(id)).isEqualTo("10");
    }

    @Test
    void loadDoesNotOverwriteNewerChange() {
        long id = service.applyChange(row(1, 12), change(150, 5)).orElseThrow();

        // 번호 150은 스냅샷 100에서 안 보인다: 객체가 이 적재보다 새롭다
        assertThat(service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY")))).isEmpty();
        assertThat(qty(id)).isEqualTo("12");
    }

    @Test
    void loadOverwritesOlderChange() {
        long id = service.applyChange(row(1, 9), change(50, 5)).orElseThrow();

        assertThat(service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY")))).contains(id);
        assertThat(qty(id)).isEqualTo("10");
    }

    @Test
    void olderGenerationDoesNotOverwriteNewer() {
        long older = newRun("RECOVERY");
        long newer = newRun("RECOVERY");
        long id = service.applyLoaded(row(1, 20), load(S200, newer)).orElseThrow();

        assertThat(service.applyLoaded(row(1, 10), load(S100, older))).isEmpty();
        assertThat(qty(id)).isEqualTo("20");
    }

    @Test
    void loadOlderThanFinishedGenerationCreatesNothing() {
        long older = newRun("RECOVERY");
        long newer = newRun("RECOVERY");
        jdbc.sql("update source_resource set full_load_done_gen = :gen where id = :id")
                .param("gen", newer)
                .param("id", resourceId)
                .update();

        assertThat(service.applyLoaded(row(1, 10), load(S100, older))).isEmpty();
        assertThat(countObjects()).isZero();
    }

    @Test
    void sameValuesKeepLastSyncButRefreshSnapshot() {
        long first = newRun("RECOVERY");
        long second = newRun("RECOVERY");
        long id = service.applyLoaded(row(1, 10), load(S100, first)).orElseThrow();

        service.applyLoaded(row(1, 10), load(S200, second));

        Map<String, Object> state =
                jdbc.sql(
                                "select last_sync_run_id, loaded_gen, loaded_snapshot::text as snap from object_instance where id = :id")
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(state)
                .containsEntry("last_sync_run_id", first)
                .containsEntry("loaded_gen", second)
                .containsEntry("snap", S200);
    }

    @Test
    void deleteWithoutObjectLeavesMarkThatBlocksOlderLoad() {
        ObjectSyncService.DeleteResult result =
                service.applyDelete(resourceId, Map.of("id", 1), change(150, 5));

        assertThat(result.markRecorded()).isTrue();
        assertThat(countObjects()).isZero();
        // 스냅샷 100은 번호 150의 삭제 전에 읽었다: 지운 행을 다시 만들지 않는다(시험 4-1의 반례)
        assertThat(service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY")))).isEmpty();
        assertThat(countObjects()).isZero();
        // 스냅샷 200은 삭제 뒤에 읽었는데 행이 있다: 같은 키로 다시 넣은 것이라 만든다
        assertThat(service.applyLoaded(row(1, 30), load(S200, newRun("RECOVERY")))).isPresent();
    }

    @Test
    void deleteMarksObjectUnlessAlreadyInLoad() {
        long id = service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY"))).orElseThrow();

        ObjectSyncService.DeleteResult older =
                service.applyDelete(resourceId, Map.of("id", 1), change(50, 5));
        assertThat(older).isEqualTo(new ObjectSyncService.DeleteResult(0, false));
        assertThat(status(id)).isEqualTo("ACTIVE");

        ObjectSyncService.DeleteResult newer =
                service.applyDelete(resourceId, Map.of("id", 1), change(150, 6));
        assertThat(newer).isEqualTo(new ObjectSyncService.DeleteResult(1, false));
        assertThat(status(id)).isEqualTo("DELETED_AT_SOURCE");
        assertThat(countMarks()).isZero();
    }

    @Test
    void finishLoadMarksMissingKeepsNewerAndClearsSeenMarks() {
        long first = newRun("RECOVERY");
        long second = newRun("RECOVERY");
        long kept = service.applyLoaded(row(1, 10), load(S100, first)).orElseThrow();
        long missing = service.applyLoaded(row(2, 10), load(S100, first)).orElseThrow();
        long createdAfter = service.applyChange(row(3, 1), change(250, 5)).orElseThrow();
        service.applyDelete(resourceId, Map.of("id", 4), change(150, 6));
        service.applyDelete(resourceId, Map.of("id", 5), change(260, 7));

        service.applyLoaded(row(1, 10), load(S200, second));
        ObjectSyncService.FinishResult result =
                service.finishLoad(resourceId, mappingId, load(S200, second));

        assertThat(result).isEqualTo(new ObjectSyncService.FinishResult(1, 1));
        assertThat(status(kept)).isEqualTo("ACTIVE");
        assertThat(status(missing)).isEqualTo("DELETED_AT_SOURCE");
        // 번호 250은 스냅샷 200 뒤의 추가라 이번 읽기에 없어도 지우지 않는다
        assertThat(status(createdAfter)).isEqualTo("ACTIVE");
        // 번호 150 흔적은 이번 읽기가 반영했으므로 지우고, 260 흔적은 남긴다
        assertThat(countMarks()).isEqualTo(1);
    }

    @Test
    void deleteLeavesMarkForMappingStillWaitingForLoad() {
        // 매핑 A의 객체는 있고, 같은 표에 새로 만든 매핑 B는 아직 적재 전이다
        long objectOfA =
                service.applyLoaded(row(1, 10), load(S100, newRun("RECOVERY"))).orElseThrow();
        long typeB = newObjectType();
        long mappingB = newMapping(typeB);

        ObjectSyncService.DeleteResult result =
                service.applyDelete(resourceId, Map.of("id", 1), change(150, 5));

        assertThat(result).isEqualTo(new ObjectSyncService.DeleteResult(1, true));
        assertThat(status(objectOfA)).isEqualTo("DELETED_AT_SOURCE");
        // 삭제 전에 읽은 매핑 B의 적재가 늦게 와도 지운 행을 만들지 않는다
        SyncedObject rowForB =
                new SyncedObject(
                        resourceId, mappingB, typeB, Map.of("id", 1), "B-1", Map.of("qty", 10));
        assertThat(service.applyLoaded(rowForB, load(S100, newRun("RECOVERY")))).isEmpty();
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new FullLoad("abc", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceChange(2, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncedObject(1, 1, 1, Map.of(), "이름", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FullLoad("100:105:101,103", 7).generation()).isEqualTo(7);
    }

    private SyncedObject row(int id, int qty) {
        return new SyncedObject(
                resourceId, mappingId, typeId, Map.of("id", id), "WO-" + id, Map.of("qty", qty));
    }

    private SourceChange change(long txid, long lsn) {
        return new SourceChange(txid, lsn, cdcRun);
    }

    private FullLoad load(String snapshot, long runId) {
        return new FullLoad(snapshot, runId);
    }

    private long newObjectType() {
        return jdbc.sql(
                        "insert into object_type (code, name) values ('EQ-' || gen_random_uuid(), '설비') returning id")
                .query(Long.class)
                .single();
    }

    private long newMapping(long objectTypeId) {
        return jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, '["id"]') returning id
                        """)
                .param("resource", resourceId)
                .param("type", objectTypeId)
                .query(Long.class)
                .single();
    }

    private long newRun(String triggerKind) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind)
                        select data_source_id, :kind from source_resource where id = :resource
                        returning id
                        """)
                .param("kind", triggerKind)
                .param("resource", resourceId)
                .query(Long.class)
                .single();
    }

    private String qty(long id) {
        return jdbc.sql("select properties->>'qty' from object_instance where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String status(long id) {
        return jdbc.sql("select status from object_instance where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private int countObjects() {
        return jdbc.sql("select count(*) from object_instance where source_mapping_id = :mapping")
                .param("mapping", mappingId)
                .query(Integer.class)
                .single();
    }

    private int countMarks() {
        return jdbc.sql(
                        "select count(*) from source_delete_mark where source_resource_id = :resource")
                .param("resource", resourceId)
                .query(Integer.class)
                .single();
    }
}
