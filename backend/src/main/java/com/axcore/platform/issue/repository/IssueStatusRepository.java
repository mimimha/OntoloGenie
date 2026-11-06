package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.domain.IssueStatus;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 문제 상태 변경에 필요한 재검증 확인과 처리 이력 저장. 문제 행의 잠금·상태 변경은 {@link IssueRepository}와 Entity가 맡는다.
 *
 * <ul>
 *   <li>재검증 확인 세 메서드: 다른 도메인 테이블(check_run)과 issue_verification·issue_history를 한 번의 SQL로 join·검사하는
 *       조회다.
 *   <li>{@link #insertHistory}: Entity를 두지 않은 issue_history의 저장이다. 문제 등록 이력({@code
 *       IssueWriteRepository})과 같은 방식을 따른다.
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class IssueStatusRepository {

    private final JdbcClient jdbc;

    /**
     * 해결 근거로 쓸 수 있는 재검증인지 확인한다.
     *
     * <ul>
     *   <li>REVERIFY이고 SUCCEEDED인 검사에서 이 문제가 PASSED였다.
     *   <li>그 검사가 이 문제를 조치완료·재검증대기로 바꾼 뒤에 시작했다. 고친 자료를 읽은 검사여야 한다.
     *   <li>그 뒤에 시작한 검사에서 이 문제가 다시 FAILED가 나오지 않았다.
     * </ul>
     */
    public boolean isUsableReverification(long issueId, long checkRunId) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1
                            from issue_verification v
                            join check_run r on r.id = v.check_run_id
                            where v.issue_id = :issueId
                              and v.check_run_id = :runId
                              and v.outcome = 'PASSED'
                              and r.trigger_kind = 'REVERIFY'
                              and r.status = 'SUCCEEDED'
                              and r.started_at >= (
                                  select max(h.changed_at)
                                  from issue_history h
                                  where h.issue_id = :issueId
                                    and h.to_status = 'FIXED_PENDING_REVERIFY'
                              )
                              and not exists (
                                  select 1
                                  from issue_verification later
                                  join check_run later_run on later_run.id = later.check_run_id
                                  where later.issue_id = :issueId
                                    and later.outcome = 'FAILED'
                                    and later_run.started_at > r.started_at
                              )
                        )
                        """)
                .param("issueId", issueId)
                .param("runId", checkRunId)
                .query(Boolean.class)
                .single();
    }

    /** 이 재검증에서 이 문제가 FAILED로 기록됐는지. 검사가 상태를 되돌릴 근거다. */
    public boolean hasFailedVerification(long issueId, long checkRunId) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1
                            from issue_verification v
                            join check_run r on r.id = v.check_run_id
                            where v.issue_id = :issueId
                              and v.check_run_id = :runId
                              and v.outcome = 'FAILED'
                              and r.trigger_kind = 'REVERIFY'
                        )
                        """)
                .param("issueId", issueId)
                .param("runId", checkRunId)
                .query(Boolean.class)
                .single();
    }

    /** 이 검사가 문제를 마지막으로 조치완료·재검증대기로 바꾼 뒤에 시작했는지. 그 전에 시작한 검사는 고치기 전 자료를 읽었다. */
    public boolean startedAfterLastFix(long issueId, long checkRunId) {
        return jdbc.sql(
                        """
                        select coalesce(r.started_at >= (
                            select max(h.changed_at)
                            from issue_history h
                            where h.issue_id = :issueId
                              and h.to_status = 'FIXED_PENDING_REVERIFY'
                        ), false)
                        from check_run r
                        where r.id = :runId
                        """)
                .param("issueId", issueId)
                .param("runId", checkRunId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void insertHistory(
            long issueId,
            IssueStatus from,
            IssueStatus to,
            String note,
            Long checkRunId,
            Long changedBy,
            Instant now) {
        jdbc.sql(
                        """
                        insert into issue_history (
                            issue_id, from_status, to_status, note, check_run_id, changed_by, changed_at
                        )
                        values (:issueId, :from, :to, :note, :runId, :changedBy, :now)
                        """)
                .param("issueId", issueId)
                .param("from", from.name())
                .param("to", to.name())
                .param("note", note, Types.VARCHAR)
                .param("runId", checkRunId, Types.BIGINT)
                .param("changedBy", changedBy, Types.BIGINT)
                .param("now", Timestamp.from(now))
                .update();
    }
}
