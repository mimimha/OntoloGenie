package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageStatus;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentAnswerServiceTests {
    private static final AgentActionOutcome PLAN_CREATED =
            new AgentActionOutcome(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "SUCCEEDED",
                    512L,
                    Map.of("planId", 3, "itemCount", 1),
                    null);
    private static final AgentActionOutcome PLAN_BLOCKED =
            new AgentActionOutcome(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "BLOCKED",
                    513L,
                    Map.of(),
                    "PLAN_CREATE 권한이 없습니다.");
    private static final QuestionInterpretation INTERPRETATION =
            new QuestionInterpretation("재배정", List.of("PR-03"), null, null, List.of(), null);

    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final AgentAnswerService answers = new AgentAnswerService(messages);

    @Test
    void numbersCandidatesThatLookTheSame() {
        String text =
                AgentAnswerService.clarifyingQuestion(
                        new QuestionInterpretation(
                                "상태", List.of("PR-03"), null, null, List.of(), null),
                        List.of(
                                new AgentTargetResolver.TargetIssue(
                                        "PR-03",
                                        List.of(
                                                new AgentReferenceObject(306L, "PR-03", "설비"),
                                                new AgentReferenceObject(406L, "PR-03", "설비"),
                                                new AgentReferenceObject(506L, "PR-03", "작업지시")))));

        // 이름·유형이 같은 후보만 번호를 붙여 사용자가 "번호 N"으로 고를 수 있게 한다.
        assertThat(text).contains("PR-03(설비, 번호 306), PR-03(설비, 번호 406), PR-03(작업지시).");
    }

    @Test
    void asksWhichObjectWhenTargetIsNotSettled() {
        AgentMessage answer = runningAnswer();
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(
                        new AgentTargetResolver.TargetIssue(
                                "06번",
                                List.of(
                                        new AgentReferenceObject(306L, "PR-06", "설비"),
                                        new AgentReferenceObject(406L, "CNC-06", "설비"))),
                        new AgentTargetResolver.TargetIssue("99번", List.of()));

        answers.answerClarification(
                900L,
                new QuestionInterpretation(
                        "실행 이력", List.of("06번", "99번"), null, null, List.of(), null),
                issues,
                List.of());

        assertThat(answer.getContent())
                .contains("'06번'에 해당하는 대상이 여러 개입니다: PR-06(설비), CNC-06(설비). 어느 것인지 알려 주세요.")
                .contains("'99번'에 해당하는 대상을 찾지 못했습니다.");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification =
                (Map<String, Object>) answer.getAnswerMeta().get("clarification");
        // 대상은 말했지만 객체로 정하지 못했으므로 대상을 되묻는 것으로 남긴다. 다음 질문은 이 되묻기의 답으로 본다.
        assertThat(clarification.get("missing")).isEqualTo(List.of("TARGET"));
        assertThat(clarification.get("targets"))
                .isEqualTo(issues.stream().map(AgentTargetResolver.TargetIssue::toMap).toList());
    }

    @Test
    void savesActionsRunDuringAnswer() {
        AgentMessage answer = runningAnswer();

        answers.answerWithResults(
                900L, "재배정 계획을 만들었습니다.", INTERPRETATION, List.of(), List.of(PLAN_CREATED));

        assertThat(answer.getAnswerMeta().get("actions")).isEqualTo(List.of(PLAN_CREATED.toMap()));
    }

    @Test
    void keepsActionsWhenAnswerFailsAfterRunningThem() {
        AgentMessage answer = runningAnswer();

        answers.failWithResults(900L, List.of(), List.of(PLAN_CREATED));

        // 계획은 이미 만들어졌으므로 실패 답변에도 남기고, 다시 질문하라고 하지 않는다(160).
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(answer.getErrorMessage()).isEqualTo(AgentAnswerService.FAILED_REASON);
        assertThat(answer.getContent()).isEqualTo(AgentAnswerService.ACTIONS_DONE_FAILED_CONTENT);
        assertThat(answer.getAnswerMeta())
                .containsEntry("syncStatus", "NONE")
                .containsEntry("results", List.of())
                .containsEntry("actions", List.of(PLAN_CREATED.toMap()));
    }

    @Test
    void asksToRetryOnlyWhenNothingWasExecuted() {
        AgentMessage answer = runningAnswer();

        answers.failWithResults(900L, List.of(), List.of(PLAN_BLOCKED));

        // 막힌 작업은 실행되지 않았으므로 다시 질문해도 계획이 겹치지 않는다.
        assertThat(answer.getContent()).isEqualTo(AgentAnswerService.FAILED_CONTENT);
    }

    @Test
    void attachesLateActionsToAnswerAlreadyClosedByCleanup() {
        AgentMessage answer = closedByCleanup();

        answers.answerWithResults(
                900L, "재배정 계획을 만들었습니다.", INTERPRETATION, List.of(), List.of(PLAN_CREATED));

        // 결과로 덮지는 않지만, 만든 계획은 답변에 덧붙여 화면에서 사라지지 않게 한다(160).
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(answer.getErrorMessage()).isEqualTo(AgentMessageService.STALE_REASON);
        assertThat(answer.getContent()).isEqualTo(AgentAnswerService.ACTIONS_DONE_FAILED_CONTENT);
        assertThat(answer.getAnswerMeta())
                .containsEntry("results", List.of())
                .containsEntry("actions", List.of(PLAN_CREATED.toMap()));
    }

    @Test
    void attachesLateActionsWhenFailingAnswerAlreadyClosedByCleanup() {
        AgentMessage answer = closedByCleanup();

        answers.failWithResults(900L, List.of(), List.of(PLAN_CREATED));

        // 실패 저장 경로에서도 같은 방식으로 덧붙인다. 실패 이유는 정리 때 값을 그대로 둔다.
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(answer.getErrorMessage()).isEqualTo(AgentMessageService.STALE_REASON);
        assertThat(answer.getContent()).isEqualTo(AgentAnswerService.ACTIONS_DONE_FAILED_CONTENT);
        assertThat(answer.getAnswerMeta()).containsEntry("actions", List.of(PLAN_CREATED.toMap()));
    }

    @Test
    void attachesBlockedActionWithoutChangingCleanupNotice() {
        AgentMessage answer = closedByCleanup();

        answers.failWithResults(900L, List.of(), List.of(PLAN_BLOCKED));

        // 막힌 작업은 실행되지 않았으므로 막힌 이유는 보여 주되, 정리 안내 문구는 그대로 둔다.
        assertThat(answer.getContent()).isEqualTo(AgentMessageService.STALE_CONTENT);
        assertThat(answer.getAnswerMeta()).containsEntry("actions", List.of(PLAN_BLOCKED.toMap()));
    }

    @Test
    void leavesClosedAnswerAsIsWhenNoActionRan() {
        AgentMessage answer = closedByCleanup();

        answers.failWithResults(900L, List.of(), List.of());

        assertThat(answer.getContent()).isEqualTo(AgentMessageService.STALE_CONTENT);
        assertThat(answer.getAnswerMeta()).containsEntry("actions", List.of());
    }

    @Test
    void keepsActionsKeyInEveryFinishedAnswer() {
        AgentMessage clarification = runningAnswer();
        answers.answerClarification(
                900L,
                new QuestionInterpretation(null, List.of(), null, null, List.of(), null),
                List.of(),
                List.of());

        // 화면이 답변 종류와 상관없이 같은 모양으로 읽도록 actions는 늘 있다(빈 목록 포함).
        assertThat(clarification.getAnswerMeta()).containsEntry("actions", List.of());

        AgentMessage failed = AgentMessage.runningAnswer(1L, Instant.parse("2026-11-09T01:00:00Z"));
        failed.fail(
                AgentAnswerService.NOT_CONFIGURED_CONTENT,
                AgentAnswerService.NOT_CONFIGURED_REASON);
        assertThat(failed.getAnswerMeta()).containsEntry("actions", List.of());
    }

    /** 처리가 5분을 넘어 정리가 먼저 실패로 끝낸 답변. 정리와 같은 문구·이유를 쓴다. */
    private AgentMessage closedByCleanup() {
        AgentMessage answer = runningAnswer();
        answer.fail(AgentMessageService.STALE_CONTENT, AgentMessageService.STALE_REASON);
        return answer;
    }

    private AgentMessage runningAnswer() {
        AgentMessage answer = AgentMessage.runningAnswer(1L, Instant.parse("2026-11-09T01:00:00Z"));
        when(messages.findForUpdateById(900L)).thenReturn(Optional.of(answer));
        return answer;
    }
}
