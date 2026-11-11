package com.axcore.platform.mapping.service;

import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.mapping.dto.MappingPreviewAccepted;
import com.axcore.platform.mapping.dto.MappingPreviewRequest;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.Draft;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.PropertyDraft;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository.Accepted;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 040 객체 매핑 시험 변환 접수(FN-MAP-04). 백엔드는 원천에 접속하지 않는다. 초안을 지금 정의와 맞춰 본 뒤 {@code source_preview}에
 * MAPPING_PREVIEW 작업을 넣고, 배치가 SYNC 계정으로 표본 행을 읽어 바꾼다. 업무 객체·연결·매핑은 저장하지 않는다.
 *
 * <ul>
 *   <li>같은 관리자가 같은 테이블에 같은 초안·같은 행 수로 넣은 작업이 대기·실행 중이면 새로 넣지 않고 합류한다. 초안은 빈 칸을 기본값으로 채운 모양으로 저장해
 *       비교한다.
 *   <li>누가 어느 테이블을 미리 봤는지는 감사 기록(SOURCE_PREVIEW)에 남긴다. 행 값과 초안 내용은 넣지 않는다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MappingPreviewService {

    private final MappingPreviewPlanner planner;
    private final SourcePreviewRepository previewRepository;
    private final AuditRecorder audits;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional
    public MappingPreviewAccepted request(
            long resourceId, MappingPreviewRequest request, LoginAccount account) {
        planner.plan(resourceId, request.mapping());
        int rowLimit = request.rowLimitOrDefault();
        String requestJson = json.writeValueAsString(stored(request.mapping()));
        Instant now = Instant.now(clock);

        previewRepository.lockRequester(account.id(), resourceId);
        Optional<Accepted> active =
                previewRepository.findActiveMappingPreview(
                        resourceId, account.id(), requestJson, rowLimit, now);
        boolean joined = active.isPresent();
        Accepted accepted =
                active.orElseGet(
                        () ->
                                previewRepository.insertMappingPreview(
                                        resourceId, account.id(), requestJson, rowLimit, now));
        audits.record(
                AuditEntry.builder()
                        .eventKind(EventKind.SOURCE_PREVIEW)
                        .actor(Actor.user(account))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .checkedPermissionCode("ADMIN")
                        .targetType(QueuedJobType.SOURCE_PREVIEW.table())
                        .targetId(accepted.id())
                        .input(
                                Map.of(
                                        "previewKind", "MAPPING_PREVIEW",
                                        "sourceResourceId", resourceId,
                                        "rowLimit", rowLimit))
                        .result(Map.of("joined", joined, "status", accepted.status()))
                        .build());
        return new MappingPreviewAccepted(
                accepted.id(),
                accepted.status(),
                joined,
                accepted.requestedAt(),
                accepted.expiresAt());
    }

    /** 저장할 요청 모양 {@code {"mapping": 초안}}. 빈 칸은 기본값으로 채워 같은 초안이 같은 jsonb가 되게 한다. */
    static Map<String, Object> stored(Draft draft) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("objectTypeId", draft.objectTypeId());
        mapping.put("keyColumns", draft.keyColumns());
        mapping.put("rowFilter", draft.rowFilterOrEmpty());
        mapping.put(
                "propertyMappings",
                draft.propertyMappingsOrEmpty().stream()
                        .map(MappingPreviewService::stored)
                        .toList());
        return Map.of("mapping", mapping);
    }

    private static Map<String, Object> stored(PropertyDraft property) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("propertyDefinitionId", property.propertyDefinitionId());
        stored.put("sourceColumnId", property.sourceColumnId());
        stored.put("sourcePath", property.sourcePath());
        List<TransformStepPayload> steps =
                property.transformSteps() == null ? List.of() : property.transformSteps();
        stored.put("transformSteps", steps.stream().map(TransformStepPayload::toStored).toList());
        return stored;
    }
}
