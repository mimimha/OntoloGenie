package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.dto.IssueVerificationResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** 문제 재검증 이력. Entity가 없는 issue_verification에 조건부 insert하고, 이력 조회에는 검사 실행 정보를 한 번의 조회로 조인한다. */
@Repository
@RequiredArgsConstructor
public class IssueVerificationRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public boolean insertIfAbsent(
            long issueId,
            long checkRunId,
            String outcome,
            Map<String, Object> detail,
            Instant now) {
        return jdbc.sql(
                                """
                        insert into issue_verification (issue_id, check_run_id, outcome, detail, checked_at)
                        values (:issueId, :runId, :outcome, cast(:detail as jsonb), :checkedAt)
                        on conflict (issue_id, check_run_id) do nothing
                        """)
                        .param("issueId", issueId)
                        .param("runId", checkRunId)
                        .param("outcome", outcome)
                        .param("detail", json.writeValueAsString(detail))
                        .param("checkedAt", Timestamp.from(now))
                        .update()
                == 1;
    }

    public Page<IssueVerificationResponse> findByIssue(long issueId, Pageable pageable) {
        long total =
                jdbc.sql("select count(*) from issue_verification where issue_id = :issueId")
                        .param("issueId", issueId)
                        .query(Long.class)
                        .single();
        List<IssueVerificationResponse> content =
                jdbc.sql(
                                """
                                select v.issue_id, v.check_run_id, r.trigger_kind, r.status,
                                       v.outcome, v.detail, v.checked_at
                                from issue_verification v
                                join check_run r on r.id = v.check_run_id
                                where v.issue_id = :issueId
                                order by v.checked_at desc, v.check_run_id desc
                                limit :size offset :offset
                                """)
                        .param("issueId", issueId)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query(
                                (rs, rowNum) ->
                                        new IssueVerificationResponse(
                                                rs.getLong("issue_id"),
                                                rs.getLong("check_run_id"),
                                                rs.getString("trigger_kind"),
                                                rs.getString("status"),
                                                rs.getString("outcome"),
                                                json.readTree(rs.getString("detail")),
                                                rs.getTimestamp("checked_at").toInstant()))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }
}
