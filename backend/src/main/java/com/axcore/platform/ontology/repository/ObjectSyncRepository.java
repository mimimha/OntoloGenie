package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.FullLoad;
import com.axcore.platform.ontology.domain.SourceChange;
import com.axcore.platform.ontology.domain.SyncedObject;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집이 쓰는 원천 객체(object_instance)와 원천 삭제 흔적(source_delete_mark) 저장.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): 객체 쓰기는 모두 <b>조건부 upsert</b>다. 전체 읽기의 스냅샷과
 * CDC 이벤트의 트랜잭션 번호를 {@code pg_visible_in_snapshot}으로 비교하는 판단과 쓰기를 한 문장으로 해야, 같은 객체를 동시에 쓰는 전체 읽기와
 * CDC 소비자가 행 잠금으로 줄을 선다. source_delete_mark는 Entity를 두지 않는 표다. object_source_mapping·
 * source_resource는 같은 문장 안에서 존재·세대 검사로만 읽는다.
 *
 * <p>판단 규칙(결정 2026-11-05·06, V11):
 *
 * <ul>
 *   <li>CDC 이벤트: 번호가 객체의 loaded_snapshot에서 보이면 이미 적재값에 들어 있으므로 쓰지 않는다. source_lsn 이하는 재전송이다.
 *   <li>전체 읽기: 객체의 source_txid가 스냅샷에서 보일 때만, 그리고 옛 세대가 아닐 때만 쓴다. 객체가 없으면 S 이후에 지워진 흔적이 있는지 본다.
 *   <li>값이 같으면 last_synced_at·last_sync_run_id·updated_at은 바꾸지 않는다. 판단용 칸(스냅샷·세대·번호·LSN)은 늘 갱신한다.
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class ObjectSyncRepository {

    /** 같은 원천 테이블의 전체 읽기 잠금 이름(ERD 9-6 "sync:resource:{id}"). */
    private static final String RESOURCE_LOCK = "sync:resource:";

    /** DO UPDATE 안에서 내용이 바뀌었는지. o는 기존 행, excluded는 넣으려던 행이다. */
    private static final String CONTENT_CHANGED =
            "(o.display_name is distinct from excluded.display_name"
                    + " or o.properties is distinct from excluded.properties"
                    + " or o.status <> 'ACTIVE')";

    /** 내용이 바뀌었을 때만 갱신하는 칸. */
    private static final String TOUCH_IF_CHANGED =
            "last_synced_at = case when "
                    + CONTENT_CHANGED
                    + " then now() else o.last_synced_at end,"
                    + " last_sync_run_id = case when "
                    + CONTENT_CHANGED
                    + " then excluded.last_sync_run_id else o.last_sync_run_id end,"
                    + " updated_at = case when "
                    + CONTENT_CHANGED
                    + " then now() else o.updated_at end";

    /** 원천 테이블의 켜진 매핑. 삭제 이벤트에는 원천 키만 있어 이 테이블의 어느 매핑 객체인지 여기서 찾는다. */
    private static final String ACTIVE_MAPPINGS_OF_RESOURCE =
            "(select m.id from object_source_mapping m"
                    + " where m.source_resource_id = :resource and m.is_active)";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** 전체 읽기 쓰기 동안 같은 테이블의 삭제 흔적 기록을 막는다(트랜잭션 끝까지). */
    public void lockResourceForLoad(long sourceResourceId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:key))")
                .param("key", RESOURCE_LOCK + sourceResourceId)
                .query()
                .singleRow();
    }

    /** CDC 소비자끼리는 함께 잡고, 전체 읽기 쓰기와만 줄을 선다(트랜잭션 끝까지). */
    public void lockResourceForChange(long sourceResourceId) {
        jdbc.sql("select pg_advisory_xact_lock_shared(hashtext(:key))")
                .param("key", RESOURCE_LOCK + sourceResourceId)
                .query()
                .singleRow();
    }

    /** CDC 추가·수정 이벤트를 객체에 쓴다. 쓰지 않았으면(이미 적재값에 있음·재전송) 빈 값이다. */
    public Optional<Long> upsertChange(SyncedObject object, SourceChange change) {
        return jdbc.sql(
                        """
                        insert into object_instance as o
                            (object_type_id, source_mapping_id, source_key, display_name, properties,
                             status, source_txid, source_lsn, last_synced_at, last_sync_run_id)
                        values (:type, :mapping, cast(:key as jsonb), :name, cast(:properties as jsonb),
                                'ACTIVE', cast(cast(:txid as text) as xid8), :lsn, now(), :run)
                        on conflict (source_mapping_id, source_key) where source_mapping_id is not null
                        do update set
                            display_name = excluded.display_name,
                            properties = excluded.properties,
                            status = 'ACTIVE',
                            source_txid = excluded.source_txid,
                            source_lsn = excluded.source_lsn,
                        """
                                + TOUCH_IF_CHANGED
                                + """

                        where not (o.loaded_snapshot is not null
                                   and pg_visible_in_snapshot(excluded.source_txid, o.loaded_snapshot))
                          and (o.source_lsn is null or o.source_lsn < excluded.source_lsn)
                        returning o.id
                        """)
                .param("type", object.objectTypeId())
                .param("mapping", object.mappingId())
                .param("key", toJson(object.sourceKey()))
                .param("name", object.displayName())
                .param("properties", toJson(object.properties()))
                .param("txid", change.transactionId())
                .param("lsn", change.lsn())
                .param("run", change.syncRunId())
                .query(Long.class)
                .optional();
    }

    /** CDC 삭제 이벤트로 그 원천 행의 객체(켜진 매핑마다)를 삭제 표시한다. 바꾼 객체 수를 돌려준다. */
    public int markDeletedByChange(
            long sourceResourceId, Map<String, Object> sourceKey, SourceChange change) {
        return jdbc.sql(
                        """
                        update object_instance o
                        set status = 'DELETED_AT_SOURCE',
                            source_txid = cast(cast(:txid as text) as xid8),
                            source_lsn = :lsn,
                            last_synced_at = case when o.status <> 'DELETED_AT_SOURCE'
                                                  then now() else o.last_synced_at end,
                            last_sync_run_id = case when o.status <> 'DELETED_AT_SOURCE'
                                                    then :run else o.last_sync_run_id end,
                            updated_at = case when o.status <> 'DELETED_AT_SOURCE'
                                              then now() else o.updated_at end
                        where o.source_mapping_id in
                        """
                                + ACTIVE_MAPPINGS_OF_RESOURCE
                                + """

                          and o.source_key = cast(:key as jsonb)
                          and not (o.loaded_snapshot is not null
                                   and pg_visible_in_snapshot(cast(cast(:txid as text) as xid8),
                                                              o.loaded_snapshot))
                          and (o.source_lsn is null or o.source_lsn < :lsn)
                        """)
                .param("resource", sourceResourceId)
                .param("key", toJson(sourceKey))
                .param("txid", change.transactionId())
                .param("lsn", change.lsn())
                .param("run", change.syncRunId())
                .update();
    }

    /**
     * 이 원천 테이블의 켜진 매핑 중 그 원천 행의 객체가 아직 없는 매핑이 있는가(객체 상태 무관). 있으면 그 매핑의 전체 읽기가 지운 행을 늦게 만들 수 있으므로 삭제
     * 흔적이 필요하다. 매핑 A의 객체만 있고 새로 만든 매핑 B는 아직 적재 전인 경우가 여기에 걸린다.
     */
    public boolean hasMappingWithoutObject(long sourceResourceId, Map<String, Object> sourceKey) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from object_source_mapping m
                            where m.source_resource_id = :resource and m.is_active
                              and not exists (select 1 from object_instance o
                                              where o.source_mapping_id = m.id
                                                and o.source_key = cast(:key as jsonb)))
                        """)
                .param("resource", sourceResourceId)
                .param("key", toJson(sourceKey))
                .query(Boolean.class)
                .single();
    }

    /** 객체가 없는 원천 행의 삭제 흔적을 남긴다. 같은 키의 흔적이 있으면 더 나중 이벤트로 바꾼다. */
    public void upsertDeleteMark(
            long sourceResourceId, Map<String, Object> sourceKey, SourceChange change) {
        jdbc.sql(
                        """
                        insert into source_delete_mark as d
                            (source_resource_id, source_key, source_txid, source_lsn)
                        values (:resource, cast(:key as jsonb), cast(cast(:txid as text) as xid8), :lsn)
                        on conflict (source_resource_id, source_key) do update set
                            source_txid = excluded.source_txid,
                            source_lsn = excluded.source_lsn,
                            created_at = now()
                        where d.source_lsn < excluded.source_lsn
                        """)
                .param("resource", sourceResourceId)
                .param("key", toJson(sourceKey))
                .param("txid", change.transactionId())
                .param("lsn", change.lsn())
                .update();
    }

    /**
     * 전체 읽기에서 읽은 행을 객체에 쓴다. 쓰지 않았으면 빈 값이다. 쓰지 않는 경우는 셋이다: 객체가 이 스냅샷보다 새롭다, 이 테이블에서 더 새 세대 적재가 이미
     * 끝났다, 객체가 없는데 이 스냅샷 이후에 지워진 흔적이 있다.
     */
    public Optional<Long> upsertLoaded(SyncedObject object, FullLoad load) {
        return jdbc.sql(
                        """
                        insert into object_instance as o
                            (object_type_id, source_mapping_id, source_key, display_name, properties,
                             status, loaded_snapshot, loaded_gen, last_synced_at, last_sync_run_id)
                        select :type, :mapping, cast(:key as jsonb), :name, cast(:properties as jsonb),
                               'ACTIVE', cast(:snapshot as pg_snapshot), :gen, now(), :gen
                        where not exists (select 1 from source_resource r
                                          where r.id = :resource and r.full_load_done_gen > :gen)
                          and not exists (select 1 from source_delete_mark d
                                          where d.source_resource_id = :resource
                                            and d.source_key = cast(:key as jsonb)
                                            and not pg_visible_in_snapshot(d.source_txid,
                                                                           cast(:snapshot as pg_snapshot)))
                        on conflict (source_mapping_id, source_key) where source_mapping_id is not null
                        do update set
                            display_name = excluded.display_name,
                            properties = excluded.properties,
                            status = 'ACTIVE',
                            loaded_snapshot = excluded.loaded_snapshot,
                            loaded_gen = excluded.loaded_gen,
                        """
                                + TOUCH_IF_CHANGED
                                + """

                        where (o.source_txid is null
                               or pg_visible_in_snapshot(o.source_txid, excluded.loaded_snapshot))
                          and (o.loaded_gen is null or o.loaded_gen <= excluded.loaded_gen)
                        returning o.id
                        """)
                .param("resource", object.sourceResourceId())
                .param("type", object.objectTypeId())
                .param("mapping", object.mappingId())
                .param("key", toJson(object.sourceKey()))
                .param("name", object.displayName())
                .param("properties", toJson(object.properties()))
                .param("snapshot", load.snapshot())
                .param("gen", load.generation())
                .query(Long.class)
                .optional();
    }

    /**
     * 이번 전체 읽기에 나오지 않은 이 매핑의 객체를 삭제 표시한다. 읽은 행은 loaded_gen이 이번 세대라 빠진다. 스냅샷 이후에 CDC로 바뀐 객체(번호가 안
     * 보임)는 그대로 둔다. 바꾼 객체 수를 돌려준다.
     */
    public int markMissing(long sourceResourceId, long mappingId, FullLoad load) {
        return jdbc.sql(
                        """
                        update object_instance o
                        set status = 'DELETED_AT_SOURCE',
                            loaded_snapshot = cast(:snapshot as pg_snapshot),
                            loaded_gen = :gen,
                            last_synced_at = now(),
                            last_sync_run_id = :gen,
                            updated_at = now()
                        where o.source_mapping_id = :mapping
                          and o.status = 'ACTIVE'
                          and (o.loaded_gen is null or o.loaded_gen < :gen)
                          and (o.source_txid is null
                               or pg_visible_in_snapshot(o.source_txid, cast(:snapshot as pg_snapshot)))
                          and not exists (select 1 from source_resource r
                                          where r.id = :resource and r.full_load_done_gen > :gen)
                        """)
                .param("resource", sourceResourceId)
                .param("mapping", mappingId)
                .param("snapshot", load.snapshot())
                .param("gen", load.generation())
                .update();
    }

    /** 전체 읽기가 이미 반영한 과거(스냅샷에서 보이는 번호)의 삭제 흔적을 지운다. 지운 수를 돌려준다. */
    public int deleteMarksSeenBy(long sourceResourceId, FullLoad load) {
        return jdbc.sql(
                        """
                        delete from source_delete_mark
                        where source_resource_id = :resource
                          and pg_visible_in_snapshot(source_txid, cast(:snapshot as pg_snapshot))
                        """)
                .param("resource", sourceResourceId)
                .param("snapshot", load.snapshot())
                .update();
    }

    private String toJson(Map<String, Object> value) {
        return json.writeValueAsString(value);
    }
}
