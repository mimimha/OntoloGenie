package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.IssueReverification;
import java.time.Instant;
import java.util.List;

/**
 * 재검증(REVERIFY, 범위 ISSUES)의 문제별 판정 결과를 받는 확장 지점(Jira 246, 191 설계 Q1-4).
 *
 * <p>135(정유림)가 구현해 {@code issue_verification}을 기록하고, FAILED면 {@code
 * IssueStatusService.reopenAfterFailedReverification}으로 문제를 확인중으로 되돌린다. 구현이 없으면 검사 실행기는 규칙별 결과만
 * 저장한다.
 *
 * <p>검사 실행기가 규칙별 결과를 저장하고 검사를 SUCCEEDED로 바꾸는 <b>같은 트랜잭션</b>(complete)에서 부른다. 예외를 던지면 결과 저장도 함께 롤백되고
 * 검사는 실패로 끝난다.
 */
public interface IssueReverificationRecorder {

    /**
     * @param checkRunId 재검증 검사 실행
     * @param results 범위의 문제마다 하나, 문제 ID 오름차순
     */
    void record(long checkRunId, List<IssueReverification> results, Instant now);
}
