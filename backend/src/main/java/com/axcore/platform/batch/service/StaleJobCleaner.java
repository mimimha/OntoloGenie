package com.axcore.platform.batch.service;

import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.ClaimedJob;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.repository.QueuedJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치가 죽거나 멈춰 RUNNING으로 남은 작업을 시간 초과로 FAILED 처리한다(S15P31C106-218). 남겨 두면 대기·실행 중 유일 조건(예:
 * uq_sync_run_resource_active) 때문에 같은 대상을 다시 넣을 수 없다. {@link QueuedJobRunner}가 실패를 처리할 때와 같이
 * Handler의 {@link QueuedJobHandler#failed}로 딸린 행을 끝내고, 감사 기록 결과 줄(SYSTEM + BATCH)을 같은 트랜잭션에 남긴다.
 */
@Service
@Profile("batch")
@RequiredArgsConstructor
public class StaleJobCleaner {

    static final String TIMED_OUT = "작업이 %d분 넘게 응답하지 않아 시간 초과로 중단했습니다. 다시 실행해 주세요.";

    private final QueuedJobRepository queuedJobRepository;
    private final ObjectProvider<QueuedJobHandler<?>> handlers;
    private final AuditRecorder auditRecorder;
    private final BatchTimeoutProperties properties;
    private final Clock clock;

    /** 정리한 작업 ID. */
    @Transactional
    public List<Long> failStale(QueuedJobType type) {
        Duration timeout = properties.forType(type);
        Instant now = Instant.now(clock);
        String message = TIMED_OUT.formatted(timeout.toMinutes());
        List<ClaimedJob> jobs =
                queuedJobRepository.failStale(type, now.minus(timeout), message, now);
        QueuedJobHandler<?> handler =
                handlers.orderedStream().filter(h -> h.type() == type).findFirst().orElse(null);
        for (ClaimedJob job : jobs) {
            if (handler != null) {
                handler.failed(job.id(), message);
            }
            auditRecorder.record(
                    QueuedJobRunner.resultLine(
                            job,
                            ExecutionStatus.FAILED,
                            Map.of("errorMessage", message, "reason", "TIMEOUT")));
        }
        return jobs.stream().map(ClaimedJob::id).toList();
    }
}
