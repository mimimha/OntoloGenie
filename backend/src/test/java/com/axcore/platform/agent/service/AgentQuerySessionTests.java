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
}
