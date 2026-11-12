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

    /**
     * 이 검사가 문제를 마지막으로 조치완료·재검증대기로 바꾼 뒤의 자료를 읽었는지(Jira 376). 아래 둘을 모두 만족해야 한다.
     *
     * <ul>
     *   <li>검사가 조치완료 표시 뒤에 시작했다. CDC 표는 플랫폼이 원천을 계속 따라가고, 옛 스냅샷의 다시 읽기는 더 새 값을 덮지 못하므로(V11) 이것으로
     *       충분하다.
     *   <li>검사의 데이터 기준({@code data_basis})에 든 FULL_COMPARE 표마다, 기준 수집 실행이 조치완료 표시 뒤에 시작했다.
     *       FULL_COMPARE 표의 객체는 마지막 성공한 전체 읽기가 시작할 때의 원천 상태라서, 조치완료 뒤에 끝났더라도 그 전에 읽기 시작한 실행이면 고치기 전
     *       자료다(수동 다시 읽기가 이미 읽고 저장 중인 실행에 합류한 경우). 기준 수집이 없으면 판단할 수 없어 만족하지 않는 것으로 본다.
     * </ul>
     *
     * <p>재검증은 실행할 때 데이터 기준을 다시 잡아 저장하므로({@code CheckRunJob}), 부르는 쪽은 그 저장 뒤에 부른다.
     */
    public boolean readDataAfterLastFix(long issueId, long checkRunId) {
        return jdbc.sql(
                        """
                        with fix as (
                            select max(h.changed_at) as fixed_at
                            from issue_history h
                            where h.issue_id = :issueId
                              and h.to_status = 'FIXED_PENDING_REVERIFY'
                        )
                        select coalesce(r.started_at >= fix.fixed_at, false)
                           and not exists (
                               select 1
                               from jsonb_array_elements(
                                        coalesce(r.data_basis -> 'resources', '[]'::jsonb)) basis
                               join source_resource sr
                                 on sr.id = (basis ->> 'sourceResourceId')::bigint
                               left join sync_run s
                                 on s.id = (basis ->> 'lastSuccessfulSyncRunId')::bigint
                               where sr.sync_strategy = 'FULL_COMPARE'
                                 and (s.started_at is null or s.started_at < fix.fixed_at)
                           )
                        from check_run r cross join fix
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
