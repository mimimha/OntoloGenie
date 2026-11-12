package com.axcore.platform.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * API 044 정의 변경 이력의 한 줄. 정의 테이블 한 행의 변경 전후와, 그 변경을 남긴 실행의 실행자·시각·Action 코드다. actionCode로 직접 편집(예:
 * UPDATE_OBJECT_TYPE)과 후보 승인(DECIDE_PROPOSAL)을 구분한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DefinitionChangeResponse(
        long id,
        long actionExecutionId,
        String tableName,
        Map<String, Object> rowKey,
        String changeKind,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        Long actorId,
        String actorType,
        Instant requestedAt,
        String actionCode) {}
