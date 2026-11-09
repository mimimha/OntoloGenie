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

    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final AgentAnswerService answers = new AgentAnswerService(messages);

    @Test
    void savesActionsRunDuringAnswer() {
        AgentMessage answer = runningAnswer();

        answers.answerWithResults(
                900L,
                "재배정 계획을 만들었습니다.",
                new QuestionInterpretation("재배정", List.of("PR-03"), null, null, List.of(), null),
                List.of(),
                List.of(PLAN_CREATED));

        assertThat(answer.getAnswerMeta().get("actions")).isEqualTo(List.of(PLAN_CREATED.toMap()));
    }

    @Test
    void keepsActionsWhenAnswerFailsAfterRunningThem() {
        AgentMessage answer = runningAnswer();

        answers.failWithResults(
                900L,
                AgentAnswerService.FAILED_CONTENT,
                AgentAnswerService.FAILED_REASON,
                List.of(),
                List.of(PLAN_CREATED));

        // 계획은 이미 만들어졌으므로 실패 답변에도 남겨, 화면이 그 계획을 보여 주게 한다(160).
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(answer.getErrorMessage()).isEqualTo(AgentAnswerService.FAILED_REASON);
        assertThat(answer.getAnswerMeta())
                .containsEntry("syncStatus", "NONE")
                .containsEntry("results", List.of())
                .containsEntry("actions", List.of(PLAN_CREATED.toMap()));
    }

    private AgentMessage runningAnswer() {
        AgentMessage answer = AgentMessage.runningAnswer(1L, Instant.parse("2026-11-09T01:00:00Z"));
        when(messages.findById(900L)).thenReturn(Optional.of(answer));
        return answer;
    }
}
