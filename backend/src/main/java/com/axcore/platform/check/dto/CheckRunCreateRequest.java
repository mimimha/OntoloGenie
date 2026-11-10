package com.axcore.platform.check.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * API 060 수동 검사·재검증 요청. triggerKind와 scope의 조합은 서비스가 확인한다.
 *
 * @param comparedToRunId REVERIFY일 때 비교할 이전 검사. MANUAL이면 보내지 않는다
 */
public record CheckRunCreateRequest(
        @NotNull(message = "triggerKind는 필수입니다.")
                @Pattern(
                        regexp = "MANUAL|REVERIFY",
                        message = "triggerKind는 MANUAL 또는 REVERIFY입니다.")
                String triggerKind,
        @NotNull(message = "scope는 필수입니다.") @Valid Scope scope,
        @Min(value = 1, message = "comparedToRunId는 1 이상이어야 합니다.") Long comparedToRunId) {

    /**
     * 검사 범위.
     *
     * <ul>
     *   <li>ALL: 반영 중인 모든 연결 규칙
     *   <li>LINK_MAPPINGS: linkMappingIds의 규칙 개정본
     *   <li>ISSUES: issueIds 문제가 나온 규칙(REVERIFY만)
     * </ul>
     */
    public record Scope(
            @NotNull(message = "scope.kind는 필수입니다.")
                    @Pattern(
                            regexp = "ALL|LINK_MAPPINGS|ISSUES",
                            message = "scope.kind는 ALL, LINK_MAPPINGS, ISSUES 중 하나입니다.")
                    String kind,
            @Size(max = 100, message = "scope.linkMappingIds는 100개 이하입니다.")
                    List<
                                    @NotNull(message = "scope.linkMappingIds에 null을 넣을 수 없습니다.")
                                    @Min(value = 1, message = "scope.linkMappingIds는 1 이상이어야 합니다.")
                                    Long>
                            linkMappingIds,
            @Size(max = 100, message = "scope.issueIds는 100개 이하입니다.")
                    List<
                                    @NotNull(message = "scope.issueIds에 null을 넣을 수 없습니다.")
                                    @Min(value = 1, message = "scope.issueIds는 1 이상이어야 합니다.") Long>
                            issueIds) {}
}
