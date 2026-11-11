package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 답변 하나를 만드는 동안의 도구 호출 모음. 조회 도구는 호출마다 순번을 매겨 기록하고 결과를 answerMeta.results로 모은다. Action 도구의 결과는
 * answerMeta.actions로 모은다(160). 답변 하나에만 쓰고 버린다.
 */
@Slf4j
public class AgentQuerySession {
    static final String QUERY_FAILED = "조회하지 못했습니다.";
    static final String NOT_TARGET = "이 질문의 대상 객체가 아니라 조회하지 않았습니다. '참고 객체 ID'에 있는 객체만 조회할 수 있습니다.";

    /** 객체 하나를 조회하는 도구의 대상 종류. */
    static final String OBJECT_INSTANCE = "object_instance";

    private final LoginAccount asker;
    private final long questionId;
    private final AgentQueryRecorder recorder;
    private final Clock clock;
    private final List<AgentQueryResult> results = new ArrayList<>();
    private final List<AgentActionOutcome> actions = new ArrayList<>();
    private boolean unrecorded;
    // 이 답변에서 남긴 조회 기록의 순번. 서버 조회(lookup)와 도구 조회(run)가 함께 쓴다.
    private int sequence;
    // 객체별 도구로 조회할 수 있는 객체. null이면 제한하지 않는다.
    private Set<Long> allowedObjectIds;

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

    /**
     * 이번 답변에서 객체별 도구로 조회할 수 있는 객체를 정한다(147). 질문을 해석해 정한 대상 객체만 허용한다. 부르지 않으면 제한하지 않는다(답변 처리기는 늘
     * 부른다).
     */
    public synchronized void allowObjects(Collection<Long> objectIds) {
        allowedObjectIds = Set.copyOf(objectIds);
    }

    private boolean objectAllowed(Long objectId) {
        return allowedObjectIds == null || allowedObjectIds.contains(objectId);
    }

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
        if (OBJECT_INSTANCE.equals(targetType) && !objectAllowed(targetId)) {
            // 이번 답변의 대상이 아닌 객체는 조회하지 않는다. 데이터에 닿지 않았으므로 기록하지 않고, AI가 "데이터 없음"으로 말하지 않게
            // 조회하지 않은 이유를 돌려준다(147).
            return Map.of("error", NOT_TARGET);
        }
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
            // AI가 계획의 기준 시점(dataAsOf)을 조회한 데이터의 시점으로 채울 수 있게 함께 알려 준다. 모르면 null이다(160).
            reply.put(
                    "dataAsOf", outcome.dataAsOf() == null ? null : outcome.dataAsOf().toString());
        } catch (RuntimeException ex) {
            log.error("Agent 조회 도구 {} 실패 question={}", toolCode, questionId, ex);
            result =
                    new AgentQueryResult(
                            label, toolCode, false, null, List.of(), null, false, QUERY_FAILED);
            reply.put("error", QUERY_FAILED);
        }
        results.add(result);
        record(input, targetType, targetId, result, requestedAt);
        return reply;
    }

    /**
     * 서버가 답변 전에 직접 하는 조회다(147 대상 찾기). 도구 조회와 같이 순번을 매겨 기록하지만, 사용자가 물은 정보가 아니므로 정보별 결과(results)에는 넣지
     * 않는다. 조회가 실패하면 실패로 기록하고 예외를 다시 던져 답변을 실패로 끝낸다.
     */
    public synchronized Outcome lookup(
            String toolCode, String label, Map<String, Object> input, Supplier<Outcome> query) {
        Instant requestedAt = Instant.now(clock);
        Outcome outcome;
        try {
            outcome = query.get();
        } catch (RuntimeException ex) {
            log.error("Agent 서버 조회 {} 실패 question={}", toolCode, questionId, ex);
            try {
                record(
                        input,
                        null,
                        null,
                        new AgentQueryResult(
                                label, toolCode, false, null, List.of(), null, false, QUERY_FAILED),
                        requestedAt);
            } catch (RuntimeException recordEx) {
                ex.addSuppressed(recordEx);
            }
            throw ex;
        }
        record(
                input,
                null,
                null,
                new AgentQueryResult(
                        label,
                        toolCode,
                        true,
                        outcome.category(),
                        outcome.sources(),
                        outcome.dataAsOf(),
                        false,
                        null),
                requestedAt);
        return outcome;
    }

    private void record(
            Map<String, Object> input,
            String targetType,
            Long targetId,
            AgentQueryResult result,
            Instant requestedAt) {
        sequence++;
        try {
            recorder.record(
                    asker, questionId, sequence, input, targetType, targetId, result, requestedAt);
        } catch (RuntimeException ex) {
            unrecorded = true;
            throw ex;
        }
    }

    /** 조회 기록을 하나라도 남기지 못했으면 답변을 끝내지 않는다. 기록 없는 조회 답변이 나가지 않게 한다. */
    public synchronized void requireRecorded() {
        if (unrecorded) {
            throw new IllegalStateException("조회 기록을 남기지 못했습니다.");
        }
    }

    /** 모든 조회 기록을 남겼는가. 실패 답변에 조회 결과를 남길지 정할 때 쓴다(160). */
    public synchronized boolean allRecorded() {
        return !unrecorded;
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
