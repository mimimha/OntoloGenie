package com.axcore.platform.agent.service;

import java.util.Map;

/**
 * 085에서 질문을 접수했다. 커밋 뒤에 이 답변 메시지를 처리한다. 질문과 화면 조건을 함께 넘겨 다시 읽지 않는다. 직전 답변이 되묻기였으면 pending이 있고, 아니면
 * null이다(143).
 */
public record AgentAnswerRequested(
        long answerId,
        String question,
        Map<String, Object> context,
        PendingClarification pending) {}
