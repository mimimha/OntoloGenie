package com.axcore.platform.source.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.source.repository.ScheduledSyncRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 수집이 켜진 원천의 FULL_COMPARE 테이블에 주기마다 전체 읽기를 넣는다(FN-BAT-01·02, S15P31C106-318). 주기는 API 072로 정한다.
 * CDC 테이블은 CDC 소비자가 반영하므로 넣지 않는다. 실제 읽기는 {@link SyncRunJob}이 하고, 수동 수집(073)과 같은 로직이다.
 *
 * <p>원천 하나에 실행 하나(trigger_kind SCHEDULED)를 넣는다. 이미 대기·실행 중인 테이블은 그 실행에 합류한 것으로 보고 넣지 않는다(217). 모든
 * 테이블이 합류했으면 실행을 남기지 않고, 다음 확인에서 다시 본다. 원천마다 트랜잭션을 나눠, 한 원천을 넣지 못해도 다른 원천은 넣는다.
 */
@Service
@Profile("batch")
public class ScheduledSyncService {

    /** 한 번에 넣는 원천 수 상한. 남은 원천은 다음 확인에서 넣는다. */
    static final int MAX_PER_RUN = 20;

    private final ScheduledSyncRepository repository;
    private final AuditRecorder audits;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ScheduledSyncService(
            ScheduledSyncRepository repository,
            AuditRecorder audits,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.audits = audits;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 넣은 실행 ID. */
    public List<Long> queueDueSyncs() {
        Instant now = Instant.now(clock);
        List<Long> queued = new ArrayList<>();
        for (long dataSourceId : repository.findDueSources(now, MAX_PER_RUN)) {
            Long runId = transactionTemplate.execute(status -> queue(dataSourceId, now, status));
            if (runId != null) {
                queued.add(runId);
            }
        }
        return queued;
    }

    private Long queue(long dataSourceId, Instant now, TransactionStatus status) {
        if (!repository.lockIfDue(dataSourceId, now)) {
            return null;
        }
        List<Long> targets = repository.findTargetResources(dataSourceId);
        long runId = repository.insertScheduledRun(dataSourceId, now);
        List<Long> inserted =
                List.copyOf(
                        new TreeSet<>(
                                repository.insertFullResources(runId, dataSourceId, targets)));
        if (inserted.isEmpty()) {
            status.setRollbackOnly();
            return null;
        }
        List<Long> joined = targets.stream().filter(id -> !inserted.contains(id)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "QUEUED");
        result.put("sourceResourceIds", inserted);
        result.put("joinedSourceResourceIds", joined);
        audits.record(
                AuditEntry.builder()
                        .eventKind(QueuedJobType.SYNC_RUN.eventKind())
                        .actor(Actor.system())
                        .entryPoint(EntryPoint.BATCH)
                        .status(ExecutionStatus.SUCCEEDED)
                        .targetType(QueuedJobType.SYNC_RUN.table())
                        .targetId(runId)
                        .requestedAt(now)
                        .input(Map.of("dataSourceId", dataSourceId, "triggerKind", "SCHEDULED"))
                        .result(result)
                        .build());
        return runId;
    }
}
