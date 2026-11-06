package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceRowConverterTests {

    @Test
    void truncatesByCodePointWithoutSplittingSurrogatePairs() {
        String text = "가".repeat(299) + "😀😀";

        String cut = SourceRowConverter.truncate(text, 300);

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(300);
        assertThat(cut).isEqualTo("가".repeat(299) + "😀");
        assertThat(SourceRowConverter.truncate("WO-0001", 300)).isEqualTo("WO-0001");
    }
}
