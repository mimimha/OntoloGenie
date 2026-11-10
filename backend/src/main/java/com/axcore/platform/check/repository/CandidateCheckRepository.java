package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Entity가 없는 검사 실행 표와 여러 도메인의 후보·원천 매핑을 한 SQL로 조회하기 위해 JdbcClient를 사용한다. */
@Repository
@RequiredArgsConstructor
public class CandidateCheckRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public List<ResourceCheckpoint> loadBasis(
            long proposalId, int revision, Set<Long> objectTypeIds, Long analysisRunId) {
        // 빈 IN 목록을 피하되 실제 ID와 겹치지 않는 값을 쓴다.
        Set<Long> types = objectTypeIds.isEmpty() ? Set.of(-1L) : objectTypeIds;
        return jdbc.sql(
                        """
                with own_payloads as (
                    select payload from proposal_revision where proposal_item_id=:proposal and revision=:revision
                    union all
                    select payload from proposal_option where proposal_item_id=:proposal and revision=:revision
                    union all
                    select payload from proposal_item where analysis_run_id=:analysis
                        and item_kind in ('OBJECT_TYPE','OBJECT_MAPPING','PROPERTY_MAPPING')
                        and review_status <> 'REJECTED'
                ), candidate_payloads as (
                    -- AI 객체 후보는 객체 매핑(objectMapping)과 속성별 원천 컬럼(properties[].sourceColumn)을 함께 담는다(API-105).
                    select payload from own_payloads
                    union all
                    select payload->'objectMapping' from own_payloads
                    where jsonb_typeof(payload->'objectMapping')='object'
                    union all
                    select property from own_payloads,
                        jsonb_array_elements(case when jsonb_typeof(payload->'properties')='array'
                            then payload->'properties' else '[]'::jsonb end) property
                ), resource_references as (
                    select payload->>'sourceId' source_code, payload->>'resourceKey' resource_key
                    from candidate_payloads
                    union
                    select payload#>>'{sourceColumn,sourceId}', payload#>>'{sourceColumn,resourceKey}'
                    from candidate_payloads
                ), resources as (
                    select source_resource_id from object_source_mapping
                    where object_type_id in (:types)
                    union
                    select source_resource_id from analysis_run_target where analysis_run_id=:analysis
                    union
                    select sr.id from resource_references ref
                    join data_source ds on ds.code=ref.source_code
                    join source_resource sr on sr.data_source_id=ds.id and sr.resource_key=ref.resource_key
                )
                select r.source_resource_id, (
                    select s.sync_run_id from sync_run_resource s
                    where s.source_resource_id=r.source_resource_id and s.status='SUCCEEDED'
                    order by s.finished_at desc, s.sync_run_id desc limit 1
                ) last_sync
                from resources r order by r.source_resource_id
                """)
                .param("proposal", proposalId)
                .param("revision", revision)
                .param("types", types)
                .param("analysis", analysisRunId, java.sql.Types.BIGINT)
                .query(
                        (rs, row) ->
                                new ResourceCheckpoint(
                                        rs.getLong("source_resource_id"),
                                        rs.getObject("last_sync", Long.class)))
                .list();
    }

    public Optional<CandidateRevision> findRevision(long checkRunId) {
        return jdbc.sql(
                        """
                select p.id, r.revision, p.item_kind, p.analysis_run_id, r.payload::text as payload,
                       exists (
                           select 1 from proposal_option o
                           where o.proposal_item_id = p.id and o.revision = r.revision
                       ) as has_options
                from check_run run
                join proposal_revision r
                    on r.proposal_item_id = (run.scope #>> '{proposalItems,0,proposalItemId}')::bigint
                    and r.revision = (run.scope #>> '{proposalItems,0,revision}')::integer
                join proposal_item p on p.id = r.proposal_item_id
                where run.id = :id and run.trigger_kind = 'CANDIDATE'
                    and jsonb_array_length(run.scope->'proposalItems') = 1
                """)
                .param("id", checkRunId)
                .query(
                        (rs, row) ->
                                new CandidateRevision(
                                        rs.getLong("id"),
                                        rs.getInt("revision"),
                                        rs.getString("item_kind"),
                                        rs.getObject("analysis_run_id", Long.class),
                                        json.readTree(rs.getString("payload")),
                                        rs.getBoolean("has_options")))
                .optional();
    }

    public List<ActiveMapping> findActiveMappings(String typeCode, Set<Long> resourceIds) {
        return jdbc.sql(
                        """
                select m.source_resource_id, d.code as source_code, m.key_columns::text as key_columns,
                       m.row_filter <> '{}'::jsonb as has_filter,
                       coalesce(jsonb_agg(jsonb_build_object(
                           'code', p.code, 'dataType', p.data_type,
                           'column', case when c.is_present then c.column_name end,
                           'transformSteps', pm.transform_steps
                       ) order by p.id) filter (where p.id is not null), '[]'::jsonb)::text as properties
                from object_source_mapping m
                join object_type t on t.id = m.object_type_id
                join source_resource r on r.id = m.source_resource_id
                join data_source d on d.id = r.data_source_id
                left join property_source_mapping pm on pm.source_mapping_id = m.id
                left join property_definition p on p.id = pm.property_definition_id
                left join source_column c on c.id = pm.source_column_id
                where t.code = :code and m.is_active and m.source_resource_id in (:resources)
                group by m.id, d.code
                order by m.id
                """)
                .param("code", typeCode)
                .param("resources", resourceIds)
                .query(
                        (rs, row) ->
                                new ActiveMapping(
                                        rs.getLong("source_resource_id"),
                                        rs.getString("source_code"),
                                        json.readTree(rs.getString("key_columns")),
                                        rs.getBoolean("has_filter"),
                                        json.readTree(rs.getString("properties"))))
                .list();
    }

    public List<MappingProposal> findMappingProposals(long analysisRunId) {
        return jdbc.sql(
                        """
                select p.item_kind, r.payload::text as payload
                from proposal_item p
                join proposal_revision r on r.proposal_item_id = p.id and r.revision = p.revision
                where p.analysis_run_id = :analysis
                    and p.item_kind in ('OBJECT_TYPE', 'OBJECT_MAPPING', 'PROPERTY', 'PROPERTY_MAPPING', 'LINK_TYPE')
                    and p.review_status in ('PENDING', 'HELD')
                    and not exists (
                        select 1 from proposal_option o
                        where o.proposal_item_id = p.id and o.revision = p.revision
                    )
                order by p.id
                """)
                .param("analysis", analysisRunId)
                .query(
                        (rs, row) ->
                                new MappingProposal(
                                        rs.getString("item_kind"),
                                        json.readTree(rs.getString("payload"))))
                .list();
    }

    public record CandidateRevision(
            long proposalId,
            int revision,
            String kind,
            Long analysisRunId,
            JsonNode payload,
            boolean hasOptions) {}

    public record ActiveMapping(
            long resourceId,
            String sourceCode,
            JsonNode keyColumns,
            boolean hasFilter,
            JsonNode properties) {}

    public record MappingProposal(String kind, JsonNode payload) {}

    public long enqueue(
            long id, int revision, Long accountId, String workKey, Object basis, Instant now) {
        var existing =
                jdbc.sql(
                                """
                select id from check_run where work_key=:key and status in ('QUEUED','RUNNING','SUCCEEDED')
                order by case when status='SUCCEEDED' then 0 else 1 end, id desc limit 1
                """)
                        .param("key", workKey)
                        .query(Long.class)
                        .optional();
        if (existing.isPresent()) return existing.get();
        return jdbc.sql(
                        """
                insert into check_run(trigger_kind, status, scope, work_key, data_basis, requested_by, requested_at)
                values ('CANDIDATE','QUEUED',
                    jsonb_build_object('proposalItems',jsonb_build_array(jsonb_build_object('proposalItemId',cast(:id as bigint),'revision',cast(:revision as integer)))),
                    :key, cast(:basis as jsonb), :account, :now)
                on conflict (work_key) where status in ('QUEUED','RUNNING')
                do update set work_key=excluded.work_key returning id
                """)
                .param("id", id)
                .param("revision", revision)
                .param("key", workKey)
                .param("basis", json.writeValueAsString(basis))
                .param("account", accountId, java.sql.Types.BIGINT)
                .param("now", java.sql.Timestamp.from(now))
                .query(Long.class)
                .single();
    }
}
