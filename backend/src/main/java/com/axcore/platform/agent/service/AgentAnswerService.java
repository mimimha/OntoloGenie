package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentMessage;
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
    private static final String NOT_QUERIED = "실제 데이터 조회는 아직 연결되지 않아 값은 알려 드릴 수 없습니다.";

    private final AgentMessageRepository messages;

    /** 기능 흐름 4단계(조회) 전까지의 답변: 이해한 조건만 알려 준다. 값은 지어내지 않는다(145). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerUnderstood(long answerId, QuestionInterpretation interpretation) {
        running(answerId)
                .ifPresent(
                        answer ->
                                answer.succeed(
                                        understoodAnswer(interpretation),
                                        Map.of(
                                                "syncStatus", "NONE",
                                                "results", List.of(),
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

    static String understoodAnswer(QuestionInterpretation interpretation) {
        String targets =
                interpretation.targets().isEmpty()
                        ? "화면에서 고른 객체"
                        : String.join(", ", interpretation.targets());
        String period =
                interpretation.hasPeriod()
                        ? interpretation.from() + " ~ " + interpretation.to()
                        : "지정하지 않음";
        String purpose = interpretation.purpose();
        return """
                질문을 이렇게 이해했습니다.
                - 대상: %s
                - 기간: %s
                - 알고 싶은 것: %s
                %s"""
                .formatted(targets, period, purpose, NOT_QUERIED);
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
