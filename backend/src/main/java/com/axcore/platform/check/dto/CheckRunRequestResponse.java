package com.axcore.platform.check.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * API 060 응답. 검사 실행 필드에 합류·재사용 여부를 더한다.
 *
 * @param joined 대기·실행 중인 같은 기준의 검사에 합류했는지(HTTP 202)
 * @param reused 같은 기준으로 성공한 검사를 다시 썼는지(HTTP 200)
 */
public record CheckRunRequestResponse(
        @JsonUnwrapped CheckRunResponse run, boolean joined, boolean reused) {}
