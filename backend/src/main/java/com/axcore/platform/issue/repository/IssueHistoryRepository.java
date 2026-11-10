package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.dto.IssueHistoryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 문제 상태 이력 읽기 전용 조회(API 069). JdbcClient: issue_history는 Entity를 두지 않은 표다. 저장도 {@link
 * IssueStatusRepository#insertHistory}·{@code IssueWriteRepository}가 JdbcClient로 한다.
 */
@Repository
@RequiredArgsConstructor
public class IssueHistoryRepository {

    private final JdbcClient jdbc;

    /** 바뀐 순서대로 준다. 같은 시각이면 먼저 넣은 행이 먼저다. */
    public Page<IssueHistoryResponse> findByIssue(long issueId, Pageable pageable) {
        long total =
                jdbc.sql("select count(*) from issue_history where issue_id = :id")
                        .param("id", issueId)
                        .query(Long.class)
                        .single();
        List<IssueHistoryResponse> content =
                jdbc.sql(
                                """
                                select id, issue_id, from_status, to_status, changed_at,
                                       changed_by, check_run_id, note
                                from issue_history
                                where issue_id = :id
                                order by changed_at, id
                                limit :size offset :offset
                                """)
                        .param("id", issueId)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query(
                                (rs, rowNum) ->
                                        new IssueHistoryResponse(
                                                rs.getLong("id"),
                                                rs.getLong("issue_id"),
                                                rs.getString("from_status"),
                                                rs.getString("to_status"),
                                                rs.getTimestamp("changed_at").toInstant(),
                                                rs.getObject("changed_by", Long.class),
                                                rs.getObject("check_run_id", Long.class),
                                                rs.getString("note")))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }
}
