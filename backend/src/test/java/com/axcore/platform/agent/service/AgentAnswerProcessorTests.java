package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

class AgentAnswerProcessorTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final AgentAnswerRequested EVENT =
            new AgentAnswerRequested(
                    900L, 899L, ASKER, "PR-03 작업을 PR-01로 옮겨줘", Map.of(), null, null);
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
    private final AgentTargetResolver targetResolver = mock(AgentTargetResolver.class);
    private final AgentAnswerProcessor processor =
            new AgentAnswerProcessor(
                    answers,
                    interpreter,
                    responder,
                    recorder,
                    targetResolver,
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
                .answer(any(), any(), any(), any());

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
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        // 기록 없는 조회 결과는 보여 주지 않고, 실행기가 따로 기록한 Action만 남긴다(160).
        verify(answers).failWithResults(900L, List.of(), List.of(PLAN_CREATED));
    }

    @Test
    void failsWithoutResultsWhenQuestionCannotBeInterpreted() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("AI 응답 시간 초과"));

        processor.onRequested(EVENT);

        verify(answers)
                .fail(900L, AgentAnswerService.FAILED_CONTENT, AgentAnswerService.FAILED_REASON);
        verify(answers, never()).failWithResults(anyLong(), any(), any());
    }

    @Test
    void continuesPreviousTargetWhenOnlyPeriodChanges() {
        AgentAnswerRequested followUp = followUp("그럼 지난달은?");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-09-30"),
                                List.of(),
                                null));
        when(responder.answer(any(), any(), any(), any())).thenReturn("9월 비가동은 1건입니다.");

        processor.onRequested(followUp);

        // 대상은 다시 찾지 않고 이전 객체를 그대로 쓰며, 기간만 바뀐다.
        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.targets()).containsExactly("PR-03");
        assertThat(used.referenceObjectIds()).containsExactly(301L);
        assertThat(used.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(used.to()).isEqualTo(LocalDate.parse("2026-09-30"));
        // 되묻기에 대한 답이 아니므로 되물은 내용은 넘기지 않는다.
        verify(responder).answer(eq("그럼 지난달은?"), any(), any(), isNull());
    }

    @Test
    void switchesToNewTargetInsteadOfPreviousObject() {
        AgentAnswerRequested followUp = followUp("06번의 실행 이력은?");
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "실행 이력", List.of("06번"), null, null, List.of(), null));
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        when(targetResolver.resolve(eq(List.of("06번")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("PR-06 실행 이력은 없습니다.");

        processor.onRequested(followUp);

        // 다른 대상을 물었으므로 처음 객체(301)를 함께 넘기지 않는다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.referenceObjects()).containsExactly(pr06);
        assertThat(used.targets()).containsExactly("06번");
    }

    @Test
    void letsToolsQueryOnlyObjectsThisAnswerTargets() {
        when(interpreter.isConfigured()).thenReturn(true);
        // "현재 진행 중인 작업지시를 보여줘"처럼 종류 전체를 물어 대상 객체가 없는 질문
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "진행 중인 작업지시",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of("작업지시")));
        List<Map<String, Object>> replies = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            AgentQuerySession session = invocation.getArgument(2);
                            // AI가 대상이 없는데 객체 0의 실행 이력을 부른 경우(로컬 시험에서 본 경우)
                            replies.add(
                                    session.run(
                                            "OBJECT_EXECUTIONS",
                                            "실행 이력",
                                            Map.of("objectId", 0L),
                                            "object_instance",
                                            0L,
                                            () -> {
                                                throw new AssertionError("조회하면 안 된다");
                                            }));
                            return "작업지시 목록은 아직 조회할 수 없습니다.";
                        })
                .when(responder)
                .answer(any(), any(), any(), any());

        processor.onRequested(EVENT);

        // 조회하지 않고 이유를 돌려주어, "데이터 없음"으로 답하지 않게 한다. 정보별 결과에도 남기지 않는다.
        assertThat(replies).containsExactly(Map.of("error", AgentQuerySession.NOT_TARGET));
        verify(answers).answerWithResults(eq(900L), any(), any(), eq(List.of()), any());
    }

    @Test
    void asksBackWhenTargetMatchesSeveralObjects() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "실행 이력", List.of("06번"), null, null, List.of(), null));
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(
                        new AgentTargetResolver.TargetIssue(
                                "06번",
                                List.of(
                                        new AgentReferenceObject(306L, "PR-06", "설비"),
                                        new AgentReferenceObject(406L, "CNC-06", "설비"))));
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), issues));

        processor.onRequested(EVENT);

        verify(answers).answerClarification(eq(900L), any(), eq(issues), any());
        verify(responder, never()).answer(any(), any(), any(), any());
    }

    @Test
    void keepsSettledObjectWhenAnsweringOnlyTheAskedTarget() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        // "PR-03과 06번 비교" 중 06번만 되물었고, 사용자가 "PR-06"이라고 답했다.
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비교", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of(pr06));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null, List.of("PR-06"), null, null, List.of(), null));
        when(targetResolver.resolve(eq(List.of("PR-06")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("비교 답변");

        processor.onRequested(
                new AgentAnswerRequested(900L, 899L, ASKER, "PR-06", Map.of(), pending, null));

        QuestionInterpretation used = answeredWith();
        assertThat(used.targets()).containsExactly("PR-03", "PR-06");
        assertThat(used.referenceObjects()).containsExactly(pr03, pr06);
        assertThat(used.purpose()).isEqualTo("비교");
        // 답변 AI가 "PR-06"을 새 질문이 아니라 되물은 것에 대한 답으로 읽게 되물은 내용을 함께 넘긴다.
        verify(responder).answer(eq("PR-06"), any(), any(), eq("'06번'에 해당하는 대상이 여러 개입니다."));
    }

    @Test
    void asksAgainForUnsettledTargetWhenReplyGivesOnlyPeriod() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비교", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of());
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        List<AgentTargetResolver.TargetIssue> stillOpen =
                List.of(new AgentTargetResolver.TargetIssue("06번", List.of()));
        when(targetResolver.resolve(eq(List.of("06번")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), stillOpen));

        processor.onRequested(
                new AgentAnswerRequested(900L, 899L, ASKER, "10월", Map.of(), pending, null));

        // 정하지 못한 06번을 빼고 PR-03만으로 답하지 않고 다시 묻는다. 정한 PR-03은 남긴다.
        ArgumentCaptor<QuestionInterpretation> asked =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(answers).answerClarification(eq(900L), asked.capture(), eq(stillOpen), any());
        assertThat(asked.getValue().referenceObjects()).containsExactly(pr03);
        assertThat(asked.getValue().hasPeriod()).isTrue();
        verify(responder, never()).answer(any(), any(), any(), any());
    }

    @Test
    void keepsScreenObjectForNextReplyWhenFirstQuestionTargetIsUnsettled() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "작업 내용",
                                List.of("WO-1"),
                                null,
                                null,
                                List.of(AgentReferenceObject.idOnly(301L)),
                                null));
        List<AgentTargetResolver.TargetIssue> issues =
                List.of(new AgentTargetResolver.TargetIssue("WO-1", List.of()));
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(), issues));

        // Explorer 객체 301 화면에서 처음 질문했다.
        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        "이 설비의 WO-1 작업 알려줘",
                        Map.of("referenceObjectIds", List.of(301)),
                        null,
                        null));

        // 화면 객체는 첫 질문에만 오므로, 되물을 때 다음 답에서 먼저 고를 객체로 남긴다.
        verify(answers)
                .answerClarification(
                        eq(900L),
                        any(),
                        eq(issues),
                        eq(List.of(AgentReferenceObject.idOnly(301L))));
    }

    /** PR-03을 찾지 못하고 "최근"의 범위도 몰라 되물은 상태(로컬 시험에서 본 경우). */
    private static PendingClarification unfoundPr03() {
        return new PendingClarification(
                "'PR-03'에 해당하는 대상을 찾지 못했습니다.",
                new QuestionInterpretation("비가동 이력", List.of("PR-03"), null, null, List.of(), "최근"),
                List.of("PR-03"),
                List.of());
    }

    @Test
    void treatsNewQuestionAfterClarificationAsNewQuestion() {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "진행 중인 작업지시",
                                List.of(),
                                null,
                                null,
                                List.of(),
                                null,
                                List.of("작업지시")));
        when(responder.answer(any(), any(), any(), any())).thenReturn("진행 중인 작업지시 답변");

        // 되묻기 전에 끝까지 답한 답변(10월 PR-03 비가동 이력)도 있다.
        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        "현재 진행 중인 작업지시를 보여줘",
                        Map.of(),
                        unfoundPr03(),
                        followUp("").previous()));

        // 되묻기에 답하지 않고 다른 목적을 새로 물었으므로, 찾지 못한 PR-03을 다시 찾거나 "최근"을 다시 묻지 않는다.
        // 예전 답변의 기간(10월)도 몰래 이어 쓰지 않는다.
        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.kinds()).containsExactly("작업지시");
        assertThat(used.targets()).isEmpty();
        assertThat(used.vaguePeriod()).isNull();
        assertThat(used.hasPeriod()).isFalse();
        // 새 질문이므로 답변 AI에 되물은 내용을 넘기지 않는다. 넘기면 새 질문을 되묻기에 대한 답으로 읽는다.
        verify(responder).answer(eq("현재 진행 중인 작업지시를 보여줘"), any(), any(), isNull());
    }

    @Test
    void treatsPurposeAndTargetAsReplyWhenBothWereAsked() {
        // "지난달 거 보여줘"에 목적과 대상을 되물었다. 기간(지난달)은 그때 정했다.
        PendingClarification pending =
                new PendingClarification(
                        "질문을 정확히 이해하려면 몇 가지를 더 알려 주세요.",
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        // 그 전에 끝까지 답한 답변은 9월 이야기였다.
        QuestionInterpretation older =
                new QuestionInterpretation(
                        "생산 실적",
                        List.of("PR-01"),
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-30"),
                        List.of(new AgentReferenceObject(303L, "PR-01", "설비")),
                        null);
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03"), null, null, List.of(), null));
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        when(targetResolver.resolve(eq(List.of("PR-03")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr03), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("PR-03 10월 비가동 이력 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "PR-03 비가동 이력", Map.of(), pending, older));

        // 물은 대로 목적과 대상을 답했으므로 되묻기에 대한 답이다. 되물을 때 정한 기간(지난달)을 쓴다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.referenceObjects()).containsExactly(pr03);
        assertThat(used.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(used.to()).isEqualTo(LocalDate.parse("2026-10-31"));
    }

    @Test
    void keepsSettledObjectWhenReplyRepeatsSamePurposeWithAskedTarget() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");
        AgentReferenceObject pr06 = new AgentReferenceObject(306L, "PR-06", "설비");
        // "PR-03과 06번 비가동 이력"에서 06번만 되물었다.
        PendingClarification pending =
                new PendingClarification(
                        "'06번'에 해당하는 대상이 여러 개입니다.",
                        new QuestionInterpretation(
                                "비가동 이력", List.of("PR-03", "06번"), null, null, List.of(pr03), null),
                        List.of("06번"),
                        List.of(pr06));
        when(interpreter.isConfigured()).thenReturn(true);
        // 사용자가 목적까지 다시 말해 답했다: "PR-06 비가동 이력 보여줘"
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                "최근 비가동 이력", List.of("PR-06"), null, null, List.of(), null));
        when(targetResolver.resolve(eq(List.of("PR-06")), any(), any()))
                .thenReturn(new AgentTargetResolver.Resolution(List.of(pr06), List.of()));
        when(responder.answer(any(), any(), any(), any())).thenReturn("비교 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "PR-06 비가동 이력 보여줘", Map.of(), pending, null));

        // 같은 목적을 다시 말한 것은 되묻기에 대한 답이다. 되물은 06번만 PR-06으로 바꾸고 PR-03은 남긴다.
        QuestionInterpretation used = answeredWith();
        assertThat(used.targets()).containsExactly("PR-03", "PR-06");
        assertThat(used.referenceObjects()).containsExactly(pr03, pr06);
    }

    @Test
    void answersWithWholeKindWhenReplyNamesKindInsteadOfTarget() {
        when(interpreter.isConfigured()).thenReturn(true);
        // "그냥 전체 설비로 보여줘, 10월" — 목적은 말하지 않았으므로 되묻기에 대한 답이다.
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null,
                                List.of("설비")));
        when(responder.answer(any(), any(), any(), any())).thenReturn("전체 설비 답변");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L, 899L, ASKER, "그냥 전체 설비로, 10월", Map.of(), unfoundPr03(), null));

        verify(targetResolver, never()).resolve(any(), any(), any());
        QuestionInterpretation used = answeredWith();
        assertThat(used.purpose()).isEqualTo("비가동 이력");
        assertThat(used.targets()).isEmpty();
        assertThat(used.kinds()).containsExactly("설비");
        assertThat(used.hasPeriod()).isTrue();
    }

    @Test
    void passesRecentTurnsToQuestionInterpreter() {
        List<ConversationTurn> turns =
                List.of(new ConversationTurn("PR-03 실행 이력 알려줘", "PR-03 실행 이력은 3건입니다."));
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(any(), any(), any(), any(), any()))
                .thenReturn(
                        new QuestionInterpretation(
                                null,
                                List.of(),
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-10-31"),
                                List.of(),
                                null));
        when(responder.answer(any(), any(), any(), any())).thenReturn("10월 실행 이력 답변");
        AgentAnswerRequested followUp = followUp("그럼 10월 것만");

        processor.onRequested(
                new AgentAnswerRequested(
                        900L,
                        899L,
                        ASKER,
                        followUp.question(),
                        Map.of(),
                        null,
                        followUp.previous(),
                        turns));

        // 해석 AI는 받은 최근 대화 원문을 그대로 받는다(147).
        verify(interpreter).interpret(any(), any(), any(), any(), eq(turns));
    }

    /** 직전 답변에서 PR-03(객체 301)의 10월 비가동 이력을 답한 대화의 후속 질문. */
    private static AgentAnswerRequested followUp(String question) {
        QuestionInterpretation previous =
                new QuestionInterpretation(
                        "비가동 이력",
                        List.of("PR-03"),
                        LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"),
                        List.of(new AgentReferenceObject(301L, "PR-03", "설비")),
                        null);
        return new AgentAnswerRequested(900L, 899L, ASKER, question, Map.of(), null, previous);
    }

    private QuestionInterpretation answeredWith() {
        ArgumentCaptor<QuestionInterpretation> used =
                ArgumentCaptor.forClass(QuestionInterpretation.class);
        verify(responder).answer(any(), used.capture(), any(), any());
        verify(answers).answerWithResults(eq(900L), any(), eq(used.getValue()), any(), any());
        return used.getValue();
    }

    private void interpretAs(QuestionInterpretation interpretation) {
        when(interpreter.isConfigured()).thenReturn(true);
        when(interpreter.interpret(EVENT.question(), EVENT.context(), null, null, List.of()))
                .thenReturn(interpretation);
        when(targetResolver.resolve(any(), any(), any()))
                .thenReturn(
                        new AgentTargetResolver.Resolution(
                                List.of(new AgentReferenceObject(301L, "PR-03", "설비")), List.of()));
    }
}
