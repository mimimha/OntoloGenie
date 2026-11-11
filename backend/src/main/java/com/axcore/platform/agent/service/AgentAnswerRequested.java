package com.axcore.platform.agent.service;

import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.Map;

/**
 * 085에서 질문을 접수했다. 커밋 뒤에 이 답변 메시지를 처리한다. 질문과 화면 조건을 함께 넘겨 다시 읽지 않는다. 직전 답변이 되묻기였으면 pending이 있고, 아니면
 * null이다(143). previous는 이 대화의 마지막 정상 답변에서 확정한 조건이며 없으면 null이다. 후속 질문은 여기에 이어 붙인다(147). 조회 도구는 질문한
 * 사용자(asker)의 권한으로 부르고, 기록은 질문 메시지(questionId)에 연결한다(144). recentTurns는 같은 대화의 최근 대화 원문으로, 질문 해석에만
 * 넘긴다(147).
 */
public record AgentAnswerRequested(
        long answerId,
        long questionId,
        LoginAccount asker,
        String question,
        Map<String, Object> context,
        PendingClarification pending,
        QuestionInterpretation previous,
        List<ConversationTurn> recentTurns) {

    /** 최근 대화 원문이 없는 질문. 운영 코드는 8인자 생성자를 쓰고, 이 생성자는 최근 대화와 관계없는 테스트를 짧게 쓰려고 둔다. */
    public AgentAnswerRequested(
            long answerId,
            long questionId,
            LoginAccount asker,
            String question,
            Map<String, Object> context,
            PendingClarification pending,
            QuestionInterpretation previous) {
        this(answerId, questionId, asker, question, context, pending, previous, List.of());
    }
}
