package com.axcore.platform.check.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** API 059 조회 조건. from·to는 요청 접수 시각(requestedAt) 기준이고 둘 다 포함한다. */
public record CheckRunSearchRequest(
        @Pattern(regexp = "MANUAL|SYNC|REVERIFY|CANDIDATE", message = "허용되지 않는 triggerKind 값입니다.")
                String triggerKind,
        @Pattern(regexp = "QUEUED|RUNNING|SUCCEEDED|FAILED", message = "허용되지 않는 status 값입니다.")
                String status,
        Instant from,
        Instant to,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {

    @AssertTrue(message = "from은 to보다 늦을 수 없습니다.")
    public boolean isPeriodOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }
}
