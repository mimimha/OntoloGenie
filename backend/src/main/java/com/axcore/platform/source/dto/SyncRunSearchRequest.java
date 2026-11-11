package com.axcore.platform.source.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * API 074 조회 조건. 비어 있는 조건으로는 거르지 않는다. triggerKind를 주지 않으면 CDC 묶음은 뺀다. 기간은 requestedAt 기준이며 from은
 * 포함, to는 포함하지 않는다.
 */
public record SyncRunSearchRequest(
        @Pattern(regexp = "QUEUED|RUNNING|SUCCEEDED|FAILED", message = "허용되지 않는 status 값입니다.")
                String status,
        @Pattern(
                        regexp = "CDC|MANUAL|SCHEDULED|ON_DEMAND|RECOVERY",
                        message = "허용되지 않는 triggerKind 값입니다.")
                String triggerKind,
        Instant from,
        Instant to,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(value = 100, message = "size는 100 이하여야 합니다.")
                Integer size) {

    @AssertTrue(message = "from은 to보다 늦을 수 없습니다.")
    public boolean isPeriodOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }
}
