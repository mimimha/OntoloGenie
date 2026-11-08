package com.axcore.platform.review.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Entity가 없는 check_run과 다른 도메인의 check_result를 함께 검사하는 읽기 전용 조회. */
@Repository
@RequiredArgsConstructor
public class ProposalDecisionCheckRepository {
    private final JdbcClient jdbc;

    public Optional<Long> findSuccessfulCheckRunId(long proposalId, int revision) {
        return jdbc.sql(
                        """
                    select run.id
                    from check_run run
                    join check_result result on result.check_run_id = run.id
                    where run.trigger_kind = 'CANDIDATE'
                        and run.status = 'SUCCEEDED'
                        and result.proposal_item_id = :proposalId
                        and result.proposal_item_revision = :revision
                        and run.scope @> jsonb_build_object(
                            'proposalItems', jsonb_build_array(jsonb_build_object(
                                'proposalItemId', cast(:proposalId as bigint),
                                'revision', cast(:revision as integer))))
                    order by run.requested_at desc, run.id desc
                    limit 1
                """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .query(Long.class)
                .optional();
    }

    public Optional<CheckRunStatus> findLatestCheck(long proposalId, int revision) {
        return jdbc.sql(
                        """
                select id, status
                from check_run
                where trigger_kind = 'CANDIDATE'
                    and scope @> jsonb_build_object(
                        'proposalItems', jsonb_build_array(jsonb_build_object(
                            'proposalItemId', cast(:proposalId as bigint),
                            'revision', cast(:revision as integer))))
                order by requested_at desc, id desc
                limit 1
                """)
                .param("proposalId", proposalId)
                .param("revision", revision)
                .query((rs, row) -> new CheckRunStatus(rs.getLong("id"), rs.getString("status")))
                .optional();
    }

    public record CheckRunStatus(long checkRunId, String status) {}
}
