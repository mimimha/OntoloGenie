package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 답변 하나를 만드는 동안의 도구 호출 모음. 조회 도구는 호출마다 순번을 매겨 기록하고 결과를 answerMeta.results로 모은다. Action 도구의 결과는
 * answerMeta.actions로 모은다(160). 답변 하나에만 쓰고 버린다.
 */
@Slf4j
public class AgentQuerySession {
    static final String QUERY_FAILED = "조회하지 못했습니다.";

    private final LoginAccount asker;
    private final long questionId;
    private final AgentQueryRecorder recorder;
    private final Clock clock;
    private final List<AgentQueryResult> results = new ArrayList<>();
    private final List<AgentActionOutcome> actions = new ArrayList<>();
    private boolean unrecorded;

    public AgentQuerySession(
            LoginAccount asker, long questionId, AgentQueryRecorder recorder, Clock clock) {
        this.asker = asker;
        this.questionId = questionId;
        this.recorder = recorder;
        this.clock = clock;
    }

    /** 조회 결과. data는 AI에게 넘길 값이다. */
    public record Outcome(
            Object data,
            AgentResultCategory category,
            List<Map<String, Object>> sources,
            Instant dataAsOf) {}

    public LoginAccount asker() {
        return asker;
    }

    public long questionId() {
        return questionId;
    }

    /**
     * 도구 하나를 실행하고 기록한다. 서버 오류·시간 초과처럼 응답을 받지 못했을 때만 실패이고, 그 사실을 AI에게 알려 "데이터 없음"으로 바꿔 말하지 않게 한다.
     *
     * @return AI에게 돌려줄 값
     */
    public synchronized Map<String, Object> run(
            String toolCode,
            String label,
            Map<String, Object> input,
            String targetType,
            Long targetId,
            Supplier<Outcome> query) {
        Instant requestedAt = Instant.now(clock);
        AgentQueryResult result;
        Map<String, Object> reply = new LinkedHashMap<>();
        try {
            Outcome outcome = query.get();
            result =
                    new AgentQueryResult(
                            label,
                            toolCode,
                            true,
                            outcome.category(),
                            outcome.sources(),
                            outcome.dataAsOf(),
                            false,
                            null);
            reply.put("resultCategory", outcome.category().name());
            reply.put("data", outcome.data());
        } catch (RuntimeException ex) {
            log.error("Agent 조회 도구 {} 실패 question={}", toolCode, questionId, ex);
            result =
                    new AgentQueryResult(
                            label, toolCode, false, null, List.of(), null, false, QUERY_FAILED);
            reply.put("error", QUERY_FAILED);
        }
        results.add(result);
        try {
            recorder.record(
                    asker,
                    questionId,
                    results.size(),
                    input,
                    targetType,
                    targetId,
                    result,
                    requestedAt);
        } catch (RuntimeException ex) {
            unrecorded = true;
            throw ex;
        }
        return reply;
    }

    /** 조회 기록을 하나라도 남기지 못했으면 답변을 끝내지 않는다. 기록 없는 조회 답변이 나가지 않게 한다. */
    public synchronized void requireRecorded() {
        if (unrecorded) {
            throw new IllegalStateException("조회 기록을 남기지 못했습니다.");
        }
    }

    public synchronized List<AgentQueryResult> results() {
        return List.copyOf(results);
    }

    /** Action 도구 결과를 모은다. 실행 기록은 실행기가 이미 남겼으므로 여기에는 답변에 보여 줄 값만 둔다. */
    public synchronized void addAction(AgentActionOutcome action) {
        actions.add(action);
    }

    public synchronized List<AgentActionOutcome> actions() {
        return List.copyOf(actions);
    }
}
