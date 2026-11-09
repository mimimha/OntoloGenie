package com.axcore.platform.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent가 답변 중에 부른 Action 하나의 결과. 답변의 answerMeta.actions[] 한 줄이 된다(API 084, 160). 화면은 이 값으로 성공·차단을
 * 구분하고, 재배정 계획이면 result.planId로 계획을 연다.
 *
 * @param status 실행했으면 실행 기록의 상태(SUCCEEDED·BLOCKED·FAILED), 실행하지 않았으면 CONFIRMATION_REQUIRED·NOT_RUN
 * @param executionId 실행 기록(action_execution) ID. 실행하지 않았으면 null
 * @param result 실행 결과. 재배정 계획이면 planId·itemCount, 실패면 errorMessage, 그 밖에는 빈 값
 * @param blockedReason BLOCKED일 때 막힌 이유
 */
public record AgentActionOutcome(
        String code,
        String name,
        String status,
        Long executionId,
        Map<String, Object> result,
        String blockedReason) {

    /** 실행기를 부르지 않은 경우(확인 필요, 입력을 읽지 못함, 실행 중 오류). */
    static AgentActionOutcome notExecuted(String code, String name, String status) {
        return new AgentActionOutcome(code, name, status, null, Map.of(), null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("code", code);
        action.put("name", name);
        action.put("status", status);
        action.put("executionId", executionId);
        action.put("result", result == null ? Map.of() : result);
        action.put("blockedReason", blockedReason);
        return action;
    }
}
