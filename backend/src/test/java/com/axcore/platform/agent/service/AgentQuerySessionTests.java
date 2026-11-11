package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentQuerySessionTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-06T01:00:00Z"), ZoneOffset.UTC);

    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session = new AgentQuerySession(ASKER, 11L, recorder, CLOCK);

    @Test
    void recordsEachCallInOrder() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_EXECUTIONS",
                        "실행 이력",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () ->
                                new AgentQuerySession.Outcome(
                                        List.of("row"),
                                        AgentResultCategory.FOUND,
                                        List.of(Map.of("objectId", 301L)),
                                        null));
        session.run(
                "OBJECT_EXECUTIONS",
                "실행 이력",
                Map.of("objectId", 302L),
                "object_instance",
                302L,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.NO_DATA, List.of(), null));

        assertThat(reply).containsEntry("resultCategory", "FOUND").containsKey("data");
        assertThat(session.results())
                .extracting(AgentQueryResult::resultCategory)
                .containsExactly(AgentResultCategory.FOUND, AgentResultCategory.NO_DATA);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        anyMap(),
                        eq("object_instance"),
                        eq(301L),
                        any(),
                        eq(CLOCK.instant()));
        verify(recorder).record(eq(ASKER), eq(11L), eq(2), anyMap(), any(), eq(302L), any(), any());
    }

    @Test
    void reportsFailedQueryWithoutCallingItNoData() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_EXECUTIONS",
                        "실행 이력",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () -> {
                            throw new IllegalStateException("DB 시간 초과");
                        });

        assertThat(reply).containsEntry("error", AgentQuerySession.QUERY_FAILED);
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.resultCategory()).isNull();
        assertThat(result.toMap()).containsEntry("status", "FAILED");
    }

    @Test
    void refusesToFinishWhenQueryWasNotRecorded() {
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());

        assertThatThrownBy(
                        () ->
                                session.run(
                                        "OBJECT_EXECUTIONS",
                                        "실행 이력",
                                        Map.of(),
                                        null,
                                        null,
                                        () ->
                                                new AgentQuerySession.Outcome(
                                                        List.of(),
                                                        AgentResultCategory.NO_DATA,
                                                        List.of(),
                                                        null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(session::requireRecorded).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tellsAiTheDataAsOfOfEachResult() {
        Map<String, Object> reply =
                session.run(
                        "OBJECT_DETAIL",
                        "설비 상세",
                        Map.of("objectId", 301L),
                        "object_instance",
                        301L,
                        () ->
                                new AgentQuerySession.Outcome(
                                        Map.of("code", "PR-03"),
                                        AgentResultCategory.FOUND,
                                        List.of(Map.of("objectId", 301L)),
                                        Instant.parse("2026-11-09T00:40:00Z")));

        // 재배정 계획의 기준 시점을 조회한 데이터의 시점으로 채울 수 있게 AI에게도 알려 준다(160).
        assertThat(reply).containsEntry("dataAsOf", "2026-11-09T00:40:00Z");
    }

    @Test
    void reportsWhetherEveryQueryWasRecorded() {
        doThrow(new IllegalStateException("기록 실패"))
                .when(recorder)
                .record(any(), anyLong(), anyInt(), anyMap(), any(), any(), any(), any());

        assertThat(session.allRecorded()).isTrue();
        assertThatThrownBy(
                () ->
                        session.run(
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
                                                null)));
        assertThat(session.allRecorded()).isFalse();
    }

    @Test
    void numbersServerLookupAndToolCallsTogether() {
        session.lookup(
                "TARGET_SEARCH",
                "대상 찾기",
                Map.of("q", "PR-03"),
                () ->
                        new AgentQuerySession.Outcome(
                                null, AgentResultCategory.FOUND, List.of(), null));
        session.run(
                "OBJECT_EXECUTIONS",
                "실행 이력",
                Map.of("objectId", 301L),
                "object_instance",
                301L,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.NO_DATA, List.of(), null));

        // 서버 조회도 같은 순번으로 기록하지만, 정보별 결과에는 사용자가 물은 도구 조회만 남는다(147).
        verify(recorder).record(eq(ASKER), eq(11L), eq(1), anyMap(), any(), any(), any(), any());
        verify(recorder).record(eq(ASKER), eq(11L), eq(2), anyMap(), any(), eq(301L), any(), any());
        assertThat(session.results())
                .extracting(AgentQueryResult::toolCode)
                .containsExactly("OBJECT_EXECUTIONS");
    }

    @Test
    void recordsFailedLookupAndStopsAnswer() {
        assertThatThrownBy(
                        () ->
                                session.lookup(
                                        "TARGET_SEARCH",
                                        "대상 찾기",
                                        Map.of("q", "PR-03"),
                                        () -> {
                                            throw new IllegalStateException("DB 오류");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 오류");

        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        anyMap(),
                        any(),
                        any(),
                        recorded.capture(),
                        any());
        assertThat(recorded.getValue().succeeded()).isFalse();
        assertThat(session.results()).isEmpty();
    }

    @Test
    void allowsObjectsFoundInThisAnswersResultsButNotInventedOnes() {
        session.allowObjects(List.of(301L), true);

        session.run(
                "OBJECT_SEARCH",
                "객체 검색",
                Map.of("q", "금형"),
                null,
                null,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", 415L)),
                                null));

        // 검색 결과에 나온 객체는 조회하고, 결과에 없던 ID는 계속 막는다(326).
        assertThat(detail(415L)).containsEntry("resultCategory", "FOUND");
        assertThat(detail(0L)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
    }

    @Test
    void keepsOnlyTargetsWhenFoundObjectsAreNotAllowed() {
        // 종류 전체 질문처럼 도구 결과의 객체를 더하지 않는 경우
        session.allowObjects(List.of(), false);

        session.run(
                "OBJECT_SEARCH",
                "객체 검색",
                Map.of("q", "설비"),
                null,
                null,
                () ->
                        new AgentQuerySession.Outcome(
                                List.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", 415L)),
                                null));

        assertThat(detail(415L)).isEqualTo(Map.of("error", AgentQuerySession.NOT_TARGET));
    }

    private Map<String, Object> detail(long objectId) {
        return session.run(
                "OBJECT_DETAIL",
                "객체 상세",
                Map.of("objectId", objectId),
                "object_instance",
                objectId,
                () ->
                        new AgentQuerySession.Outcome(
                                Map.of(),
                                AgentResultCategory.FOUND,
                                List.of(Map.of("objectId", objectId)),
                                null));
    }
}
