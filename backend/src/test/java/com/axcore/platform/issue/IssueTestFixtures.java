package com.axcore.platform.issue;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 문제 테스트가 쓰는 검사 실행·규칙·문제 행을 DB에 직접 만든다. */
public record IssueTestFixtures(JdbcClient jdbc) {

    public long insertAdmin() {
        return jdbc.sql(
                        """
                        insert into account (
                            login_id, display_name, password_hash, role_id, must_change_password
                        )
                        values (
                            :loginId, '문제 테스트 관리자', 'test-hash',
                            (select id from role where code = 'ADMIN'), false
                        )
                        returning id
                        """)
                .param("loginId", "issue-test-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    public long insertCandidateRun() {
        return jdbc.sql(
                        """
                        insert into check_run (trigger_kind, work_key)
                        values ('CANDIDATE', :workKey)
                        returning id
                        """)
                .param("workKey", "issue-test-run-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    /** 재검증 실행. 문제를 해결로 바꿀 때 근거로 쓴다. */
    public long insertReverifyRun(long comparedToRunId, long accountId) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, work_key, compared_to_run_id,
                            requested_by, started_at, finished_at
                        )
                        values ('REVERIFY', 'SUCCEEDED', :workKey, :comparedTo, :accountId, now(), now())
                        returning id
                        """)
                .param("workKey", "issue-test-reverify-" + UUID.randomUUID())
                .param("comparedTo", comparedToRunId)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    /** 승인된 연결 규칙 개정본 하나와 그 규칙이 쓰는 유형들을 만든다. */
    public long insertLinkMapping() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.sql(
                        """
                        with source_type as (
                            insert into object_type (code, name)
                            values (:sourceCode, '문제 테스트 출발 유형')
                            returning id
                        ),
                        target_type as (
                            insert into object_type (code, name)
                            values (:targetCode, '문제 테스트 도착 유형')
                            returning id
                        ),
                        relation_type as (
                            insert into link_type (
                                code, name, source_type_id, target_type_id, cardinality, description
                            )
                            select :linkCode, '문제 테스트 관계', s.id, t.id, 'MANY_TO_ONE', '문제 테스트'
                            from source_type s
                            cross join target_type t
                            returning id
                        )
                        insert into link_mapping (link_type_id, code, revision)
                        select id, :mappingCode, 1
                        from relation_type
                        returning id
                        """)
                .param("sourceCode", "SRC_" + suffix)
                .param("targetCode", "DST_" + suffix)
                .param("linkCode", "LINK_" + suffix)
                .param("mappingCode", "MAP_" + suffix)
                .query(Long.class)
                .single();
    }

    public long insertIssue(
            long checkRunId, long linkMappingId, String kind, String status, String evidence) {
        return jdbc.sql(
                        """
                        insert into issue (
                            issue_kind, status, dedupe_key, link_mapping_id, raw_value,
                            transformed_value, failure_reason, evidence, detected_check_run_id
                        )
                        values (
                            :kind, :status, :dedupeKey, :linkMappingId, 'W23', 'E023',
                            '변환 값 E023과 같은 직원이 없습니다.', cast(:evidence as jsonb), :runId
                        )
                        returning id
                        """)
                .param("kind", kind)
                .param("status", status)
                .param("dedupeKey", kind + ":test:" + UUID.randomUUID())
                .param("linkMappingId", linkMappingId)
                .param("evidence", evidence)
                .param("runId", checkRunId)
                .query(Long.class)
                .single();
    }

    public void setCreatedAt(long issueId, Instant createdAt) {
        jdbc.sql("update issue set created_at = :createdAt where id = :id")
                .param("createdAt", Timestamp.from(createdAt), Types.TIMESTAMP)
                .param("id", issueId)
                .update();
    }

    /** 통과한 재검증 기록을 남기고 문제를 해결로 바꾼다. */
    public void resolve(long issueId, long reverifyRunId) {
        jdbc.sql(
                        """
                        insert into issue_verification (issue_id, check_run_id, outcome)
                        values (:issueId, :runId, 'PASSED')
                        """)
                .param("issueId", issueId)
                .param("runId", reverifyRunId)
                .update();
        jdbc.sql(
                        """
                        update issue
                        set status = 'RESOLVED', resolved_check_run_id = :runId,
                            resolved_outcome = 'PASSED'
                        where id = :issueId
                        """)
                .param("issueId", issueId)
                .param("runId", reverifyRunId)
                .update();
    }
}
