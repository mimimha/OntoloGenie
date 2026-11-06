package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionInterpretationTests {

    @Test
    void asksTargetAndPurposeWhenUnknown() {
        QuestionInterpretation unknown =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), null);

        assertThat(unknown.missing())
                .containsExactly(MissingCondition.TARGET, MissingCondition.PURPOSE);
    }

    @Test
    void asksOnlyVaguePeriod() {
        QuestionInterpretation vague =
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근");
        QuestionInterpretation noPeriod =
                new QuestionInterpretation("상태", List.of(), null, null, List.of(301L), null);

        assertThat(vague.missing()).containsExactly(MissingCondition.PERIOD);
        assertThat(noPeriod.missing()).isEmpty();
    }

    @Test
    void fillsOnlyNewValuesFromReply() {
        QuestionInterpretation previous =
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근");
        QuestionInterpretation reply =
                new QuestionInterpretation(
                        null,
                        List.of(),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(),
                        null);

        QuestionInterpretation filled = reply.fillFrom(previous);

        assertThat(filled.purpose()).isEqualTo("비가동 이력");
        assertThat(filled.targets()).containsExactly("PR-03");
        assertThat(filled.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(filled.missing()).isEmpty();
    }

    @Test
    void keepsAskingVaguePeriodUntilAnswered() {
        QuestionInterpretation previous =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), "최근");
        QuestionInterpretation reply =
                new QuestionInterpretation("상태", List.of("PR-03"), null, null, List.of(), null);

        assertThat(reply.fillFrom(previous).missing()).containsExactly(MissingCondition.PERIOD);
    }

    @Test
    void readsBackSavedAnswerMeta() {
        QuestionInterpretation saved =
                new QuestionInterpretation(
                        "비가동 이력",
                        List.of("PR-03"),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(301L),
                        "최근");

        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(
                        Map.of(
                                "conditions", saved.toConditions(),
                                "clarification", saved.toClarification()));

        assertThat(read).isEqualTo(saved);
    }
}
