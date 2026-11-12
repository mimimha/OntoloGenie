package com.axcore.platform.source.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** API 072. syncEnabled가 true이면 syncIntervalMinutes(1 이상)가 필요하다. 보낸 값으로 바꾼다(생략하면 null). */
public record SyncScheduleRequest(
        @NotNull Boolean syncEnabled,
        @Min(1) Integer syncIntervalMinutes,
        @NotNull @PositiveOrZero Long rowVersion) {}
