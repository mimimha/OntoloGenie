package com.axcore.platform.source.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceKeyTextTests {

    @Test
    void formatsEachSourceTypeTheSameWayForFullLoadAndCdc() {
        assertThat(SourceKeyText.of("WO-0001")).isEqualTo("WO-0001");
        assertThat(SourceKeyText.of(12L)).isEqualTo("12");
        assertThat(SourceKeyText.of(12)).isEqualTo("12");
        assertThat(SourceKeyText.of(new BigInteger("9223372036854775808")))
                .isEqualTo("9223372036854775808");
        // 원천 소수 자릿수를 그대로 두고 지수 표기를 쓰지 않는다.
        assertThat(SourceKeyText.of(new BigDecimal("12.50"))).isEqualTo("12.50");
        assertThat(SourceKeyText.of(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(SourceKeyText.of(1.5d)).isEqualTo("1.5");
        assertThat(SourceKeyText.of(true)).isEqualTo("true");
        assertThat(SourceKeyText.of(LocalDate.of(2026, 11, 6))).isEqualTo("2026-11-06");
        // 초는 늘 쓰고 소수 초는 있을 때만 쓴다.
        assertThat(SourceKeyText.of(LocalTime.of(9, 0))).isEqualTo("09:00:00");
        assertThat(SourceKeyText.of(LocalTime.of(9, 0, 0, 500_000_000))).isEqualTo("09:00:00.5");
        assertThat(SourceKeyText.of(LocalDateTime.of(2026, 11, 6, 9, 0)))
                .isEqualTo("2026-11-06T09:00:00");
    }

    @Test
    void timestampWithZoneIsTheSameInstantWhateverTheOffset() {
        OffsetDateTime seoul = OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        OffsetDateTime utc = OffsetDateTime.of(2026, 11, 6, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(SourceKeyText.of(seoul)).isEqualTo("2026-11-06T00:00:00Z");
        assertThat(SourceKeyText.of(utc)).isEqualTo(SourceKeyText.of(seoul));
    }

    @Test
    void missingOrNonFiniteValueIsNoKey() {
        assertThat(SourceKeyText.of(null)).isNull();
        assertThat(SourceKeyText.of(Double.NaN)).isNull();
        assertThat(SourceKeyText.of(Double.POSITIVE_INFINITY)).isNull();
    }

    @Test
    void rejectsValuesThatAreNotSourceScalars() {
        assertThatThrownBy(() -> SourceKeyText.of(Map.of("a", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceKeyText.of(List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
