package com.axcore.platform.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class AgentAnswerProcessorTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final AgentAnswerRequested EVENT =
            new AgentAnswerRequested(900L, 899L, ASKER, "PR-03 작업을 PR-01로 옮겨줘", Map.of(), null);
    private static final QuestionInterpretation INTERPRETATION =
            new QuestionInterpretation("재배정", List.of("PR-03"), null, null, List.of(), null);
    private static final AgentActionOutcome PLAN_CREATED =
            new AgentActionOutcome(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "SUCCEEDED",
                    512L,
                    Map.of("planId", 3, "itemCount", 1),
                    null);

    private final AgentAnswerService answers = mock(AgentAnswerService.class);
    private final QuestionInterpreter interpreter = mock(QuestionInterpreter.class);
    private final AgentResponder responder = mock(AgentResponder.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentAnswerProcessor processor =
            new AgentAnswerProcessor(
                    answers,
                    interpreter,
                    responder,
                    recorder,
                    Clock.systemUTC(),
                    new SyncTaskExecutor());

    @Test
    void keepsActionAlreadyRunWhenAiFailsAfterward() {
        interpretAs(INTERPRETATION);
        // AI가 계획을 만든 뒤 답변 문장을 쓰다가 실패한 경우
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.addAction(PLAN_CREATED);
                            throw new IllegalStateException("AI 응답 시간 초과");
                        })
                .when(responder)
                .answer(any(), any(), any());

        processor.onRequested(EVENT);

        verify(answers).failWithResults(900L, List.of(), List.of(PLAN_CREATED));
        verify(answers, never()).fail(anyLong(), anyString(), anyString());
    }

    @Test
    void dropsQueryResultsThatWereNotRecorded() {
        interpretAs(INTERPRETATION);
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());
        // 계획을 만든 뒤 조회했는데 그 조회 기록을 남기지 못한 경우
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            session.addAction(PLAN_CREATED);
                            return session.run(
                                    "OBJECT_EXECUTIONS",
                                    "실행 이력",
                                    Map.of("objectId", 301L),
                                    "object_instance",
                                    301L,
                                    () ->
                                            new AgentQuerySession.Outcome(
                                                    List.of(),
                                                    AgentResultCategory.NO_DATA,
                                                    List.of(),
                                                    null));
                        })
                .when(responder)
                .answer(any(), any(), any());

        processor.onRequested(EVENT);

        // 기록 없는 조회 결과는 보여 주지 않고, 실행기가 따로 기록한 Action만 남긴다(160).
        verify(answers).failWithResults(900L, List.of(), List.of(PLAN_CREATED));
    }

    @Test
    void failsWithoutResultsWhenQuestionCannotBeInterpreted() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any()))
                .thenThrow(new IllegalStateException("AI 응답 시간 초과"));

        processor.onRequested(EVENT);

        verify(answers)
                .fail(900L, AgentAnswerService.FAILED_CONTENT, AgentAnswerService.FAILED_REASON);
        verify(answers, never()).failWithResults(anyLong(), any(), any());
    }

    private void interpretAs(QuestionInterpretation interpretation) {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(EVENT.question(), EVENT.context(), null))
                .thenReturn(interpretation);
    }
}
