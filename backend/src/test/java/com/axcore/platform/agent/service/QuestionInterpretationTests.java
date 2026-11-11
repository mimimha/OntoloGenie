package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionInterpretationTests {
    private static final AgentReferenceObject PR03 = new AgentReferenceObject(301L, "PR-03", "설비");
    private static final QuestionInterpretation OCTOBER_DOWNTIME =
            new QuestionInterpretation(
                    "비가동 이력",
                    List.of("PR-03"),
                    LocalDate.parse("2026-10-01"),
                    LocalDate.parse("2026-10-31"),
                    List.of(PR03),
                    null);

    @Test
    void followUpKeepsTargetAndChangesOnlyPeriod() {
        QuestionInterpretation lastMonth =
                new QuestionInterpretation(
                        null,
                        List.of(),
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-30"),
                        List.of(),
                        null);

        QuestionInterpretation continued = lastMonth.continueFrom(OCTOBER_DOWNTIME);

        assertThat(continued)
                .isEqualTo(
                        new QuestionInterpretation(
                                "비가동 이력",
                                List.of("PR-03"),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(PR03),
                                null));
    }

    @Test
    void followUpWithNewTargetDropsPreviousObject() {
        QuestionInterpretation other =
                new QuestionInterpretation("실행 이력", List.of("06번"), null, null, List.of(), null);

        QuestionInterpretation continued = other.continueFrom(OCTOBER_DOWNTIME);

        // 다른 대상을 물었으므로 이전 객체를 이어 쓰지 않는다. 기간은 이어 쓴다.
        assertThat(continued.targets()).containsExactly("06번");
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void followUpWithVaguePeriodAsksInsteadOfReusingPreviousPeriod() {
        QuestionInterpretation recent =
                new QuestionInterpretation(null, List.of(), null, null, List.of(), "최근");

        QuestionInterpretation continued = recent.continueFrom(OCTOBER_DOWNTIME);

        assertThat(continued.hasPeriod()).isFalse();
        assertThat(continued.missing()).containsExactly(MissingCondition.PERIOD);
    }

    @Test
    void readsOldAnswerWithOnlyObjectIds() {
        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(
                        Map.of(
                                "conditions",
                                Map.of(
                                        "targets",
                                        List.of("PR-03"),
                                        "referenceObjectIds",
                                        List.of(301))));

        assertThat(read.referenceObjects()).containsExactly(AgentReferenceObject.idOnly(301L));
    }

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
                new QuestionInterpretation(
                        "상태",
                        List.of(),
                        null,
                        null,
                        List.of(new AgentReferenceObject(301L, null, null)),
                        null);

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
                        List.of(new AgentReferenceObject(301L, null, null)),
                        "최근");

        QuestionInterpretation read =
                QuestionInterpretation.fromAnswerMeta(
                        Map.of(
                                "conditions", saved.toConditions(),
                                "clarification", saved.toClarification()));

        assertThat(read).isEqualTo(saved);
    }

    @Test
    void followUpAskingWholeKindDoesNotKeepPreviousTarget() {
        QuestionInterpretation allEquipment =
                new QuestionInterpretation(
                        "비가동 현황", List.of(), null, null, List.of(), null, List.of("설비"));

        QuestionInterpretation continued = allEquipment.continueFrom(OCTOBER_DOWNTIME);

        // "전체 설비 비가동 현황은?"을 PR-03으로 답하지 않는다(147).
        assertThat(continued.targets()).isEmpty();
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.kinds()).containsExactly("설비");
        assertThat(continued.from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void readsUnsettledTargetsAndPreferredObjectsFromClarification() {
        QuestionInterpretation asked =
                new QuestionInterpretation(
                        "비교", List.of("PR-03", "06번"), null, null, List.of(PR03), null);
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(new AgentTargetResolver.TargetIssue("06번", List.of(pr06)));

        PendingClarification pending =
                PendingClarification.fromAnswer(
                        "어느 것인가요?",
                        Map.of(
                                "conditions",
                                asked.toConditions(),
                                "clarification",
                                AgentAnswerService.clarification(
                                        asked,
                                        issues,
                                        List.of(AgentReferenceObject.idOnly(900L)))));

        assertThat(pending.previous().referenceObjects()).containsExactly(PR03);
        assertThat(pending.unresolvedTargets()).containsExactly("06번");
        assertThat(pending.preferred()).containsExactly(pr06, AgentReferenceObject.idOnly(900L));
    }
}
