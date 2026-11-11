package com.axcore.platform.issue.service;

import com.axcore.platform.check.domain.IssueReverification;
import com.axcore.platform.check.domain.IssueReverification.Verdict;
import com.axcore.platform.check.service.IssueReverificationRecorder;
import com.axcore.platform.issue.repository.IssueVerificationRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 검사 결과 및 상태와 같은 완료 트랜잭션에 문제별 검증 이력을 남긴다. */
@Service
@RequiredArgsConstructor
public class IssueVerificationRecorder implements IssueReverificationRecorder {

    private final IssueVerificationRepository verifications;
    private final IssueStatusService issueStatusService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long checkRunId, List<IssueReverification> results, Instant now) {
        for (IssueReverification result : results) {
            if (result.verdict() == Verdict.NOT_EVALUATED) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            detail.put("linkMappingId", result.linkMappingId());
            boolean inserted =
                    verifications.insertIfAbsent(
                            result.issueId(), checkRunId, result.verdict().name(), detail, now);
            if (inserted && result.verdict() == Verdict.FAILED) {
                issueStatusService.reopenAfterFailedReverification(result.issueId(), checkRunId);
            }
        }
    }
}
