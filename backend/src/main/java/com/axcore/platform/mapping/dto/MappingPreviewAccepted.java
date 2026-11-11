package com.axcore.platform.mapping.dto;

import java.time.Instant;

/**
 * API 040 응답(SourcePreviewAccepted). 결과는 102로 받는다.
 *
 * @param status QUEUED 또는 RUNNING. 합류했으면 그 작업의 상태
 * @param joined 대기·실행 중인 같은 미리보기에 합류했는지
 * @param expiresAt 결과가 지워지는 시각(요청 1시간 뒤)
 */
public record MappingPreviewAccepted(
        long previewId, String status, boolean joined, Instant requestedAt, Instant expiresAt) {}
