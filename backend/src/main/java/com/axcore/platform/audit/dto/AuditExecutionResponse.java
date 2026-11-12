package com.axcore.platform.audit.dto;

import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 실행·감사 이력 한 줄 (API 096·080). 표시 이름(actionCode·actionName·actorName·targetLabel)과 종류(category)는 서버가
 * 채운다. 입력·결과·막힌 이유는 097, 변경 내역은 098로 본다(S15P31C106-374). changeCount는 그 실행이 바꾼 행 수다.
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
        ExecutionStatus status,
        Instant requestedAt,
        Instant finishedAt,
        int changeCount) {}
