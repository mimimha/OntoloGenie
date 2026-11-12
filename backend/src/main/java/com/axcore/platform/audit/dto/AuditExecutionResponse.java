package com.axcore.platform.audit.dto;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실행·감사 이력 한 줄 (API 096·080). 표시 이름(actionCode·actionName·actorName·targetLabel)과 종류(category)는 서버가
 * 채운다. input·result·변경 내용은 기록할 때 이미 비밀값을 뺀 값이다. restrictedPropertyCodes는 사용자가 볼 수 없어 값을 가린 민감 속성
 * 코드다(S15P31C106-205). 가린 것이 없으면 빈 목록이다. changeCount는 그 실행이 바꾼 행 수다(가림과 무관).
 */
public record AuditExecutionResponse(
        Long id,
        UUID requestId,
        EventKind eventKind,
        ExecutionCategory category,
        Long actionDefinitionId,
        String actionCode,
        String actionName,
        ActorType actorType,
        Long actorId,
        String actorName,
        String actorRoleCode,
        String checkedPermissionCode,
        EntryPoint entryPoint,
        String targetType,
        Long targetId,
        String targetLabel,
        Map<String, Object> input,
        ExecutionStatus status,
        String blockedReason,
        Map<String, Object> result,
        Instant requestedAt,
        Instant finishedAt,
        List<EntityChangeResponse> entityChanges,
        int changeCount,
        List<String> restrictedPropertyCodes) {

    public AuditExecutionResponse withMaskedValues(
            Map<String, Object> maskedInput,
            List<EntityChangeResponse> maskedChanges,
            List<String> maskedPropertyCodes) {
        return new AuditExecutionResponse(
                id,
                requestId,
                eventKind,
                category,
                actionDefinitionId,
                actionCode,
                actionName,
                actorType,
                actorId,
                actorName,
                actorRoleCode,
                checkedPermissionCode,
                entryPoint,
                targetType,
                targetId,
                targetLabel,
                maskedInput,
                status,
                blockedReason,
                result,
                requestedAt,
                finishedAt,
                maskedChanges,
                changeCount,
                maskedPropertyCodes);
    }

    public AuditExecutionResponse withEntityChanges(List<EntityChangeResponse> changes) {
        return new AuditExecutionResponse(
                id,
                requestId,
                eventKind,
                category,
                actionDefinitionId,
                actionCode,
                actionName,
                actorType,
                actorId,
                actorName,
                actorRoleCode,
                checkedPermissionCode,
                entryPoint,
                targetType,
                targetId,
                targetLabel,
                input,
                status,
                blockedReason,
                result,
                requestedAt,
                finishedAt,
                changes,
                changes.size(),
                restrictedPropertyCodes);
    }
}
