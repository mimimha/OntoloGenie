package com.axcore.platform.ontology.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 전체 읽기 스냅샷 형식(pg_current_snapshot(): xmin:xmax:진행 중 목록) 검사. */
class FullLoadTests {

    @ParameterizedTest
    @ValueSource(strings = {"100:105:", "100:105:101", "100:105:101,103,104"})
    void acceptsSnapshotText(String snapshot) {
        assertThat(new FullLoad(snapshot, 1).snapshot()).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "100",
                "100:105",
                "100:105:101,",
                "100:105:,101",
                "a:105:",
                "100:105:101 103"
            })
    void rejectsOtherText(String snapshot) {
        assertThatThrownBy(() -> new FullLoad(snapshot, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 묶음 반복을 재귀로 처리하던 정규식은 목록이 아주 길면 StackOverflowError를 냈다(SonarQube java:S5998, Jira 332).
    @Test
    void longInProgressListDoesNotOverflowTheStack() {
        String inProgress =
                LongStream.rangeClosed(1, 200_000)
                        .mapToObj(Long::toString)
                        .collect(Collectors.joining(","));

        assertThat(new FullLoad("1:300000:" + inProgress, 1).generation()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveRunId() {
        assertThatThrownBy(() -> new FullLoad("1:2:", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
