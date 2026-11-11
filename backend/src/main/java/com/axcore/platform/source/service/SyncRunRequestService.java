package com.axcore.platform.source.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.RecoveryReason;
import com.axcore.platform.source.dto.SyncRunAcceptedResponse;
import com.axcore.platform.source.dto.SyncRunAcceptedResponse.Resource;
import com.axcore.platform.source.repository.SyncRunRequestRepository;
import com.axcore.platform.source.repository.SyncRunRequestRepository.ActiveRun;
import com.axcore.platform.source.repository.SyncRunRequestRepository.RequestedResource;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 수동 다시 읽기 접수(API 073, FN-BAT-03, S15P31C106-216·217). 고른 테이블을 trigger MANUAL, mode FULL로 넣기만
 * 하고, 실제 읽기는 배치 {@link SyncRunJob}이 한다. CDC 테이블도 전체를 다시 읽는다.
 *
 * <p>테이블마다 대기·실행 중인 실행이 있으면 새로 넣지 않고 그 실행에 합류한다. 감시 작업이 넣은 RECOVERY 실행에도 합류한다. 합류하지 않은 테이블만 새 실행
 * 하나에 묶는다. 동시 요청은 대기·실행 중 유일 조건(uq_sync_run_resource_active)에 걸린 쪽이 다시 조회해 합류한다.
 */
@Service
@RequiredArgsConstructor
public class SyncRunRequestService {

    /**
     * 테이블 하나를 넣는 시도 횟수. 조회가 없다고 했는데 넣기가 충돌하는 것은 다른 요청이 그 사이 넣고 커밋했을 때뿐이라, 다음 조회에서 합류한다. 그 실행이 그 사이
     * 끝나 한 번 더 도는 경우까지 3번이면 충분하다.
     */
    static final int MAX_ATTEMPTS = 3;

    private static final String QUEUED = "QUEUED";

    private final DataSourceService dataSourceService;
    private final SyncRunRequestRepository repository;
    private final AuditRecorder audits;
    private final Clock clock;

    @Transactional
    public SyncRunAcceptedResponse request(
            long dataSourceId, List<Long> requestedIds, LoginAccount account) {
        dataSourceService.findDataSource(dataSourceId);
        Set<Long> resourceIds = targets(dataSourceId, requestedIds);
        Instant now = Instant.now(clock);

        Long newRunId = null;
        Map<Long, Long> runByResource = new LinkedHashMap<>();
        Set<Long> joinedIds = new TreeSet<>();
        // ID 순서로 처리해 겹치는 테이블을 고른 동시 요청끼리 서로 기다리며 막히지 않게 한다.
        for (long resourceId : resourceIds) {
            for (int attempt = 1; !runByResource.containsKey(resourceId); attempt++) {
                if (attempt > MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "원천 테이블을 실행에 넣지 못했습니다: sourceResourceId=" + resourceId);
                }
                Optional<ActiveRun> active = repository.findActiveRunForUpdate(resourceId);
                if (active.isPresent()) {
                    if (!active.get().runActive()) {
                        // 배치는 실행과 테이블 행을 같은 트랜잭션에서 끝낸다. 어긋났으면 합류할 실행이 없고 새로 넣을 수도 없다.
                        throw new IllegalStateException(
                                "테이블 행은 대기 중인데 실행은 끝났습니다: syncRunId=%d, status=%s, sourceResourceId=%d"
                                        .formatted(
                                                active.get().syncRunId(),
                                                active.get().runStatus(),
                                                resourceId));
                    }
                    runByResource.put(resourceId, active.get().syncRunId());
                    joinedIds.add(resourceId);
                    continue;
                }
                if (newRunId == null) {
                    newRunId = repository.insertManualRun(dataSourceId, account.id(), now);
                }
                if (repository.insertFullResource(newRunId, dataSourceId, resourceId)) {
                    runByResource.put(resourceId, newRunId);
                }
                // 넣지 못했으면 다른 요청이 먼저 넣은 것이다. 다시 조회해 그 실행에 합류한다.
            }
        }
        if (newRunId != null && joinedIds.size() == resourceIds.size()) {
            repository.deleteRun(newRunId);
            newRunId = null;
        }

        List<Resource> resources =
                repository.findResourceRuns(runByResource).stream()
                        .map(run -> Resource.of(run, joinedIds.contains(run.sourceResourceId())))
                        .toList();
        String status = newRunId == null ? null : QUEUED;
        record(account, dataSourceId, requestedIds, newRunId, status, resources);
        return new SyncRunAcceptedResponse(
                newRunId,
                dataSourceId,
                "MANUAL",
                status,
                account.id(),
                now,
                !joinedIds.isEmpty(),
                resources);
    }

    /**
     * 대상 테이블을 정하고 검사한다. 생략하면 켜진 매핑이 있는 모든 테이블이다. 다른 원천의 테이블은 400, 켜진 매핑이 없으면 422, 원천 교체 뒤 재연결을 기다리는
     * CDC 테이블이 있으면 409다.
     */
    private Set<Long> targets(long dataSourceId, List<Long> requestedIds) {
        Set<Long> targets =
                requestedIds == null
                        ? mappedTables(dataSourceId)
                        : requested(dataSourceId, requestedIds);
        List<Long> waiting = repository.findWaitingReconnect(List.copyOf(targets));
        if (!waiting.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT,
                    Map.of(
                            "field",
                            "sourceResourceIds",
                            "reason",
                            "원천 접속 정보가 바뀌어 CDC 재연결을 기다리는 테이블입니다. 재연결 뒤 다시 요청해 주세요.",
                            "recoveryReason",
                            RecoveryReason.SOURCE_CHANGED.name(),
                            "ids",
                            waiting));
        }
        return targets;
    }

    private Set<Long> mappedTables(long dataSourceId) {
        List<Long> mapped = repository.findMappedResources(dataSourceId);
        if (mapped.isEmpty()) {
            throw unmapped("이 원천에는 켜진 매핑이 있는 테이블이 없습니다.", List.of());
        }
        return new TreeSet<>(mapped);
    }

    private Set<Long> requested(long dataSourceId, List<Long> requestedIds) {
        Set<Long> requested = new TreeSet<>(requestedIds);
        Set<Long> unknown = new TreeSet<>(requested);
        Set<Long> unmapped = new TreeSet<>();
        for (RequestedResource resource :
                repository.findRequestedResources(dataSourceId, List.copyOf(requested))) {
            unknown.remove(resource.id());
            if (!resource.mapped()) {
                unmapped.add(resource.id());
            }
        }
        if (!unknown.isEmpty()) {
            throw invalid("이 원천의 테이블이 아닙니다.", List.copyOf(unknown));
        }
        if (!unmapped.isEmpty()) {
            throw unmapped("켜진 매핑이 없는 테이블은 수집할 수 없습니다.", List.copyOf(unmapped));
        }
        return requested;
    }

    private static BusinessException invalid(String reason, List<Long> ids) {
        return new BusinessException(
                CommonErrorCode.VALIDATION_FAILED,
                Map.of("field", "sourceResourceIds", "reason", reason, "ids", ids));
    }

    /** 매핑은 요청 형식이 아니라 그때의 데이터 상태라 업무 조건 위반(422)으로 거절한다. */
    private static BusinessException unmapped(String reason, List<Long> ids) {
        return new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                Map.of("field", "sourceResourceIds", "reason", reason, "ids", ids));
    }

    /** 합류만 해서 새 실행이 없어도 요청을 남긴다. 그때는 대상(target)을 비운다. */
    private void record(
            LoginAccount account,
            long dataSourceId,
            List<Long> requestedIds,
            Long newRunId,
            String status,
            List<Resource> resources) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataSourceId", dataSourceId);
        input.put("sourceResourceIds", requestedIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put(
                "resources",
                resources.stream()
                        .map(
                                r ->
                                        Map.of(
                                                "sourceResourceId", r.sourceResourceId(),
                                                "syncRunId", r.syncRunId(),
                                                "joined", r.joined()))
                        .toList());
        audits.record(
                AuditEntry.builder()
                        .eventKind(QueuedJobType.SYNC_RUN.eventKind())
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType(newRunId == null ? null : QueuedJobType.SYNC_RUN.table())
                        .targetId(newRunId)
                        .input(input)
                        .result(result)
                        .build());
    }
}
