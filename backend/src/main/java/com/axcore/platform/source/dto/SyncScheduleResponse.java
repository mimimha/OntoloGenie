package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.DataSource;
import com.fasterxml.jackson.annotation.JsonInclude;

/** API 명세의 SyncSchedule (071·072). 이 주기는 그 원천의 FULL_COMPARE 테이블에만 쓴다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SyncScheduleResponse(
        boolean syncEnabled, Integer syncIntervalMinutes, long rowVersion) {

    public static SyncScheduleResponse from(DataSource source) {
        return new SyncScheduleResponse(
                source.isSyncEnabled(), source.getSyncIntervalMinutes(), source.getRowVersion());
    }
}
