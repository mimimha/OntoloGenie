package com.axcore.platform.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 실행 하나가 바꾼 행 전체 (API 098). 남긴 순서(id 순)이고 페이지를 나누지 않는다. restrictedPropertyCodes는 변경 내역에서 값을 가린 민감
 * 속성 코드다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditExecutionChangesResponse(
        List<AuditEntityChangeResponse> changes, List<String> restrictedPropertyCodes) {}
