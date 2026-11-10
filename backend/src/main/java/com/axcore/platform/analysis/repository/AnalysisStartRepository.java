package com.axcore.platform.analysis.repository;

import com.axcore.platform.analysis.dto.AnalysisStartRequest.PastDecision;
import com.axcore.platform.analysis.dto.AnalysisStartRequest.PreviousProposal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** AI에 분석 시작을 알릴 때 필요한 실행·대상·원천 정보를 읽는다. 계정은 source 도메인의 SourceCredentialService로 읽는다. */
@Repository
@RequiredArgsConstructor
public class AnalysisStartRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Optional<Run> findRun(long id) {
        return jdbc.sql("select run_kind, status, analysis_range from analysis_run where id=:id")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Run(
                                        rs.getString("run_kind"),
                                        rs.getString("status"),
                                        object(rs.getString("analysis_range"))))
                .optional();
    }

    public List<Target> findTargets(long runId) {
        return jdbc.sql(
                        """
                select r.id, r.resource_key, r.source_metadata,
                       d.id as data_source_id, d.code, d.source_kind, d.connection_config
                from analysis_run_target t
                join source_resource r on r.id = t.source_resource_id
                join data_source d on d.id = r.data_source_id
                where t.analysis_run_id = :runId
                order by r.id
                """)
                .param("runId", runId)
                .query(
                        (rs, row) ->
                                new Target(
                                        rs.getLong("id"),
                                        rs.getString("resource_key"),
                                        object(rs.getString("source_metadata")),
                                        rs.getLong("data_source_id"),
                                        rs.getString("code"),
                                        rs.getString("source_kind"),
                                        object(rs.getString("connection_config"))))
                .list();
    }

    /** 분석 실행과 review 도메인의 결정·개정본·선택지를 한 SQL에서 확인하므로 JdbcClient를 사용한다. */
    public List<PastDecision> findPastDecisions(long runId) {
        return jdbc.sql(
                        """
                with decision_evidence as (
                    select d.id, d.decided_at, d.item_revision, d.decision, d.reason, p.item_kind,
                           original.evidence as revision_evidence,
                           case when d.selected_option_id is null then original.evidence else o.evidence end as evidence,
                           case when p.item_kind = 'DOCUMENT_LINK'
                                     and jsonb_typeof(o.payload) = 'object' and o.payload <> '{}'::jsonb
                                then o.payload end as option_payload
                    from analysis_run current_run
                    join analysis_run past_run on past_run.id <> current_run.id
                        and past_run.status = 'SUCCEEDED' and past_run.finished_at < current_run.requested_at
                    join proposal_item p on p.analysis_run_id = past_run.id and p.origin = 'AI'
                    join review_decision d on d.proposal_item_id = p.id
                    join proposal_revision r on r.proposal_item_id = p.id and r.revision = d.item_revision
                    join proposal_revision original on original.proposal_item_id = p.id and original.revision = 1
                    left join proposal_option o on o.id = d.selected_option_id
                    where current_run.id = :runId
                      and d.decision in ('APPROVE', 'REJECT') and d.decided_at <= current_run.requested_at
                      -- 검토자가 저장한 개정본·선택지에는 AI 지문이 복사되어 있을 수 있다.
                      and (d.item_revision = 1 or (d.selected_option_id is null and r.payload = original.payload))
                ), identified_decisions as (
                    select *,
                           case when jsonb_typeof(evidence -> 'fingerprint') = 'string'
                                then nullif(btrim(evidence ->> 'fingerprint'), '') end as fingerprint
                    from decision_evidence
                ), latest_decisions as (
                    select distinct on (fingerprint, payload)
                           fingerprint,
                           case when fingerprint is null then option_payload end as payload,
                           coalesce(
                               case when jsonb_typeof(evidence -> 'candidateId') = 'string'
                                   then nullif(btrim(evidence ->> 'candidateId'), '') end,
                               case when jsonb_typeof(revision_evidence -> 'candidateId') = 'string'
                                   then nullif(btrim(revision_evidence ->> 'candidateId'), '') end
                           ) as candidate_id,
                           item_kind, item_revision, decision, reason, decided_at, id
                    from identified_decisions
                    where fingerprint is not null or option_payload is not null
                    order by fingerprint, payload, decided_at desc, id desc
                )
                select candidate_id, item_kind, item_revision, fingerprint, payload, decision, reason
                from latest_decisions
                order by decided_at, id
                """)
                .param("runId", runId)
                .query(
                        (rs, row) -> {
                            String payload = rs.getString("payload");
                            return new PastDecision(
                                    rs.getString("candidate_id"),
                                    rs.getString("item_kind"),
                                    rs.getInt("item_revision"),
                                    rs.getString("fingerprint"),
                                    payload == null ? null : object(payload),
                                    rs.getString("decision"),
                                    rs.getString("reason"));
                        })
                .list();
    }

    /** 표별 직전 실행과 review 도메인의 최초 개정본을 한 SQL에서 조회하므로 JdbcClient를 사용한다. */
    public List<PreviousProposal> findPreviousProposals(long runId) {
        return jdbc.sql(
                        """
                with previous_runs as (
                    select distinct previous.id, previous.finished_at
                    from analysis_run current_run
                    join analysis_run_target target on target.analysis_run_id = current_run.id
                    cross join lateral (
                        select r.id, r.finished_at
                        from analysis_run_target t
                        join analysis_run r on r.id = t.analysis_run_id
                        where t.source_resource_id = target.source_resource_id
                          and r.id <> current_run.id and r.status = 'SUCCEEDED'
                          and r.finished_at < current_run.requested_at
                        order by r.finished_at desc, r.id desc
                        limit 1
                    ) previous
                    where current_run.id = :runId
                )
                select distinct on (candidate_id)
                       btrim(r.evidence ->> 'candidateId') as candidate_id,
                       btrim(r.evidence ->> 'fingerprint') as fingerprint
                from previous_runs previous
                join proposal_item p on p.analysis_run_id = previous.id and p.origin = 'AI'
                join proposal_revision r on r.proposal_item_id = p.id and r.revision = 1
                where jsonb_typeof(r.evidence -> 'candidateId') = 'string'
                  and btrim(r.evidence ->> 'candidateId') <> ''
                  and jsonb_typeof(r.evidence -> 'fingerprint') = 'string'
                  and btrim(r.evidence ->> 'fingerprint') <> ''
                order by candidate_id, previous.finished_at desc, previous.id desc, p.id desc
                """)
                .param("runId", runId)
                .query(
                        (rs, row) ->
                                new PreviousProposal(
                                        rs.getString("candidate_id"), rs.getString("fingerprint")))
                .list();
    }

    private Map<String, Object> object(String value) {
        return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    public record Run(String runKind, String status, Map<String, Object> analysisRange) {}

    public record Target(
            long sourceResourceId,
            String resourceKey,
            Map<String, Object> sourceMetadata,
            long dataSourceId,
            String dataSourceCode,
            String sourceKind,
            Map<String, Object> connectionConfig) {}
}
