package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.repository.DataAsOfRepository;
import com.axcore.platform.agent.repository.DataAsOfRepository.SourceTable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 기준 시점 규칙(정책 문서 4.1·4.2, 145, 2026-11-11 이주헌 님과 정함). */
class DataAsOfResolverTests {
    private static final Instant MESSAGE = Instant.parse("2026-11-11T01:00:00Z");
    private static final Instant HEALTHY = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant FULL_START = Instant.parse("2026-11-11T00:30:00Z");

    private final DataAsOfRepository tables = mock(DataAsOfRepository.class);
    private final DataAsOfResolver resolver = new DataAsOfResolver(tables);

    @Test
    void usesLastMessageOfHealthyCdcSourceAfterTheTableWasFullyRead() {
        assertThat(DataAsOfResolver.confirmedAt(cdc(true, HEALTHY, 100L))).isEqualTo(MESSAGE);
    }

    @Test
    void cannotTellCdcTimeWhenConsumerIsMissingUnhealthyOrTableNotLoaded() {
        // 소비자가 시작하지 않음
        assertThat(DataAsOfResolver.confirmedAt(cdc(false, null, 100L))).isNull();
        // 수집 비정상·복구 필요(recovery_reason이 있으면 healthy_since도 비어 있다)
        assertThat(DataAsOfResolver.confirmedAt(cdc(true, null, 100L))).isNull();
        // 첫 적재 전·다시 읽기 대기·진행 중
        assertThat(DataAsOfResolver.confirmedAt(cdc(true, HEALTHY, null))).isNull();
    }

    @Test
    void usesStartOfLastSucceededFullReadForFullCompareTable() {
        assertThat(DataAsOfResolver.confirmedAt(full(FULL_START))).isEqualTo(FULL_START);
        assertThat(DataAsOfResolver.confirmedAt(full(null))).isNull();
    }

    @Test
    void takesEarliestTimeAndGivesUpWhenAnyTableIsUnknown() {
        assertThat(DataAsOfResolver.earliest(List.of(cdc(true, HEALTHY, 100L), full(FULL_START))))
                .isEqualTo(FULL_START);
        assertThat(DataAsOfResolver.earliest(List.of(full(FULL_START), full(null)))).isNull();
        // 원천 표가 없으면(플랫폼이 만든 객체뿐이면) 알 수 없다.
        assertThat(DataAsOfResolver.earliest(List.of())).isNull();
    }

    @Test
    void cannotTellTimeWhenMappingOfTheObjectsIsStopped() {
        // 수집을 멈춘 매핑의 객체는 표를 확인한 시각이 그 객체 값에 맞지 않는다.
        SourceTable stoppedCdc =
                new SourceTable(1L, "CDC", 100L, false, true, MESSAGE, HEALTHY, null);
        SourceTable stoppedFull =
                new SourceTable(2L, "FULL_COMPARE", null, false, false, null, null, FULL_START);

        assertThat(DataAsOfResolver.confirmedAt(stoppedCdc)).isNull();
        assertThat(DataAsOfResolver.confirmedAt(stoppedFull)).isNull();
        assertThat(DataAsOfResolver.earliest(List.of(full(FULL_START), stoppedFull))).isNull();
    }

    @Test
    void readsTablesOfObjectsAndOfBothEndsOfALink() {
        when(tables.tablesOfObjects(any())).thenReturn(List.of(full(FULL_START)));
        when(tables.tablesOfLink(9L)).thenReturn(List.of(cdc(true, HEALTHY, 100L)));

        assertThat(resolver.forObjects(List.of(301L, 301L))).isEqualTo(FULL_START);
        assertThat(resolver.forLink(9L)).isEqualTo(MESSAGE);
    }

    @Test
    void doesNotQueryWithoutObjects() {
        assertThat(resolver.forObjects(List.of())).isNull();

        verifyNoInteractions(tables);
    }

    private static SourceTable cdc(boolean hasOffset, Instant healthySince, Long loadedLsn) {
        return new SourceTable(1L, "CDC", loadedLsn, true, hasOffset, MESSAGE, healthySince, null);
    }

    private static SourceTable full(Instant lastFullStartedAt) {
        return new SourceTable(
                2L, "FULL_COMPARE", null, true, false, null, null, lastFullStartedAt);
    }
}
