package com.axcore.platform.source.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.source.repository.CdcReloadRepository;
import com.axcore.platform.source.repository.CdcReloadRepository.DueResource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다시 읽기 표시(loaded_lsn = NULL)가 붙은 CDC 테이블에 전체 읽기 실행을 넣는다(S15P31C106-330). 표시는 매핑 서비스(매핑 변경)와 CDC
 * 소비자(복구)가 붙이고, 전체 읽기가 성공해야 풀린다. 첫 적재·매핑 변경·복구를 모두 trigger_kind RECOVERY로 넣고, 넣은 이유는 감사 기록 접수
 * 줄(SYSTEM + BATCH)에 남긴다. 실제 읽기는 {@link SyncRunJob}이 한다(결정 문서 11.1).
 *
 * <p>실행 하나에 테이블 하나를 넣는다. 한 테이블의 실패가 다른 테이블의 실패 횟수를 올리지 않게 하려는 것이다. 테이블마다 트랜잭션을 나눠, 한 테이블을 넣지 못해도 다른
 * 테이블은 넣는다.
 */
@Service
@Profile("batch")
@EnableConfigurationProperties(CdcReloadProperties.class)
public class CdcReloadService {

    /** 한 번에 넣는 테이블 수 상한. 남은 테이블은 다음 주기에 넣는다. */
    static final int MAX_PER_RUN = 20;

    static final String MAPPING_CHANGED_PATTERN = likePattern(SyncRunJob.MAPPING_CHANGED);

    private final CdcReloadRepository repository;
    private final AuditRecorder audits;
    private final CdcReloadProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CdcReloadService(
            CdcReloadRepository repository,
            AuditRecorder audits,
            CdcReloadProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.audits = audits;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 넣은 실행 ID. */
    public List<Long> queueDueReloads() {
        Instant now = Instant.now(clock);
        List<DueResource> due =
                repository.findDue(
                        now.minus(properties.retryDelay()),
                        properties.maxFailures(),
                        MAPPING_CHANGED_PATTERN,
                        MAX_PER_RUN);
        List<Long> queued = new ArrayList<>();
        for (DueResource resource : due) {
            Long runId = transactionTemplate.execute(status -> queue(resource, status));
            if (runId != null) {
                queued.add(runId);
            }
        }
        return queued;
    }

    private Long queue(DueResource resource, TransactionStatus status) {
        long runId = repository.insertRecoveryRun(resource.dataSourceId());
        if (!repository.insertFullResource(runId, resource.resourceId())) {
            status.setRollbackOnly();
            return null;
        }
        audits.record(
                AuditEntry.builder()
                        .eventKind(QueuedJobType.SYNC_RUN.eventKind())
                        .actor(Actor.system())
                        .entryPoint(EntryPoint.BATCH)
                        .status(ExecutionStatus.SUCCEEDED)
                        .targetType(QueuedJobType.SYNC_RUN.table())
                        .targetId(runId)
                        .input(
                                Map.of(
                                        "dataSourceId", resource.dataSourceId(),
                                        "sourceResourceId", resource.resourceId()))
                        .result(Map.of("reason", resource.reason().name(), "status", "QUEUED"))
                        .build());
        return runId;
    }

    /** String.format 문구를 LIKE 패턴으로 바꾼다. %s 자리는 아무 문자열, 나머지는 글자 그대로다(escape 문자는 백슬래시). */
    static String likePattern(String format) {
        return Pattern.compile("%s", Pattern.LITERAL)
                .splitAsStream(format)
                .map(part -> part.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
                .collect(Collectors.joining("%"));
    }
}
