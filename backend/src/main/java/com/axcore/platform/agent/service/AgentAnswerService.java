package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.AnswerStage;
import com.axcore.platform.agent.repository.AgentMessageRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 처리 중인 답변의 결과를 저장한다. AI 호출은 이 트랜잭션 밖에서 하고 결과만 짧게 저장한다. 그 사이 5분 정리로 이미 끝난 답변은 건드리지 않는다. */
@Service
@RequiredArgsConstructor
public class AgentAnswerService {
    static final String NOT_CONFIGURED_CONTENT = "아직 AI 답변 설정이 되어 있지 않습니다. 질문은 저장되었습니다.";
    static final String NOT_CONFIGURED_REASON = "AI 설정 없음";
    static final String FAILED_CONTENT = "답변을 끝내지 못했습니다. 잠시 후 다시 질문해 주세요.";
    static final String FAILED_REASON = "AI 응답 실패";

    private static final String CLARIFY_INTRO = "질문을 정확히 이해하려면 몇 가지를 더 알려 주세요.";

    private final AgentMessageRepository messages;

    /** 처리 중 단계를 바꾼다. 화면은 084를 다시 읽어 단계 문구를 보여 준다(정책 문서 3.3). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void moveTo(long answerId, AnswerStage stage) {
        running(answerId).ifPresent(answer -> answer.moveTo(stage));
    }

    /**
     * 조회 도구로 답했다(144). 답변 문장은 AI가 도구 결과로 쓰고, 정보별 결과(results)는 서버가 남긴 조회 기록에서 만든다. 질문 시 동기화는 아직 없어
     * syncStatus는 NONE이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerWithResults(
            long answerId,
            String content,
            QuestionInterpretation interpretation,
            List<AgentQueryResult> results) {
        running(answerId)
                .ifPresent(
                        answer ->
                                answer.succeed(
                                        content,
                                        Map.of(
                                                "syncStatus", "NONE",
                                                "results",
                                                        results.stream()
                                                                .map(AgentQueryResult::toMap)
                                                                .toList(),
                                                "conditions", interpretation.toConditions())));
    }

    /** 대상·목적·기간이 부족하면 되묻는다(143). 지금까지 알아낸 조건은 conditions에 남겨 다음 답과 합친다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerClarification(long answerId, QuestionInterpretation interpretation) {
        running(answerId)
                .ifPresent(
                        answer ->
                                answer.succeed(
                                        clarifyingQuestion(interpretation),
                                        Map.of(
                                                "syncStatus", "NONE",
                                                "results", List.of(),
                                                "conditions", interpretation.toConditions(),
                                                "clarification",
                                                        interpretation.toClarification())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long answerId, String content, String reason) {
        running(answerId).ifPresent(answer -> answer.fail(content, reason));
    }

    static String clarifyingQuestion(QuestionInterpretation interpretation) {
        StringBuilder text = new StringBuilder(CLARIFY_INTRO);
        for (MissingCondition missing : interpretation.missing()) {
            text.append('\n')
                    .append(
                            switch (missing) {
                                case TARGET -> "- 어떤 설비·작업지시·공정을 말씀하시는지 알려 주세요.";
                                case PURPOSE -> "- 무엇을 알고 싶으신지 알려 주세요. (예: 상태, 비가동 이력)";
                                case PERIOD ->
                                        "- '"
                                                + interpretation.vaguePeriod()
                                                + "'이 언제부터 언제까지인지 알려 주세요. (예: 10월 1일부터 10월 31일까지)";
                            });
        }
        return text.toString();
    }

    private Optional<AgentMessage> running(long answerId) {
        return messages.findById(answerId).filter(AgentMessage::isRunning);
    }
}
