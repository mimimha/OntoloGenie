package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.repository.TestAnalysisCleanupRepository;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 분석 지우기(S15P31C106-307, 시연 전 없앨 임시 기능). 끝난 분석에서 반영되지 않고 어디에도 쓰이지 않은 제안만 지운다. 반영된 제안은 남기고, 남은
 * 제안이 없는 분석은 분석 기록까지 지운다.
 */
@Service
@RequiredArgsConstructor
public class TestAnalysisCleanupService {

    private final TestAnalysisCleanupRepository repository;
    private final AuditRecorder audits;

    /** 끝난 테스트 분석의 지울 수 있는 제안을 모두 지운다. 제안이 남은 분석은 skippedRuns, 남은 제안 수는 keptProposals로 알린다. */
    @Transactional
    public Map<String, Object> deleteAll(LoginAccount account) {
        List<Long> deleted = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        int proposals = 0;
        int kept = 0;
        for (long runId : repository.finishedRunIds()) {
            // 제안·후보 검사 행을 먼저 잠근다. 승인(결정)과 같은 순서로 잠가 동시에 눌러도 섞이지 않는다.
            repository.lockParts(runId);
            List<Long> ids = repository.deletableItems(runId);
            // 제안이 없는 분석(실패·결과 0건)도 분석 기록을 지운다
            Map<String, Integer> removed =
                    ids.isEmpty() && repository.itemCount(runId) > 0
                            ? Map.of()
                            : deleteRun(runId, ids, account);
            proposals += removed.getOrDefault("proposals", 0);
            if (removed.getOrDefault("analysisRuns", 0) > 0) {
                deleted.add(runId);
            } else {
                skipped.add(runId);
                kept += repository.itemCount(runId);
            }
        }
        return Map.of(
                "deletedRuns",
                deleted,
                "deletedProposals",
                proposals,
                "skippedRuns",
                skipped,
                "keptProposals",
                kept);
    }

    private Map<String, Integer> deleteRun(long runId, List<Long> ids, LoginAccount account) {
        Map<String, Integer> deleted = repository.delete(runId, ids);
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.ANALYSIS_RUN)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType("analysis_run")
                        .targetId(runId)
                        .input(Map.of("action", "TEST_ANALYSIS_DELETE"))
                        .result(Map.copyOf(deleted))
                        .build());
        return deleted;
    }
}
