package com.axcore.platform.source.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** API 073. sourceResourceIds를 생략하면 켜진 매핑이 있는 이 원천의 모든 테이블이 대상이다. */
public record SyncRunRequest(@Size(min = 1) List<@NotNull @Min(1) Long> sourceResourceIds) {}
