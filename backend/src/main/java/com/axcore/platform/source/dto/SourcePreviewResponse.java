package com.axcore.platform.source.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * API 102 원천 미리보기 결과(SourcePreview). 비어 있을 수 있는 칸(startedAt·finishedAt·result·errorMessage)도 null로
 * 내보낸다.
 *
 * @param previewKind SAMPLE(016) 또는 MAPPING_PREVIEW(040). result의 모양을 정한다
 * @param result SUCCEEDED일 때만. SAMPLE은 columns·rows, MAPPING_PREVIEW는 objects·rowErrors
 * @param errorMessage FAILED일 때만
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourcePreviewResponse(
        long id,
        String previewKind,
        long sourceResourceId,
        String status,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt,
        Map<String, Object> result,
        String errorMessage) {}
