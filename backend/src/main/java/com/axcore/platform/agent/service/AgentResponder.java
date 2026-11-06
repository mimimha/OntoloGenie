package com.axcore.platform.agent.service;

import com.axcore.platform.audit.service.AuditExecutionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 조회 도구와 실행할 수 있는 Action 도구를 주고 AI가 답변 문장을 쓰게 한다(144). 어떤 도구를 부를지는 AI가 고르고, 부를 때마다 서버가 기록한다. 값은 도구
 * 결과에서만 쓰게 하고, 정보별 결과 유형·기준 시점은 서버 기록으로 따로 남긴다(145).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AgentResponder {
    private static final String SYSTEM_PROMPT =
            """
            너는 제조 설비·작업 데이터 조회 Agent다. 사용자의 질문에 한국어로 짧게 답한다.
            - 값은 도구로 조회한 결과에서만 쓴다. 도구 결과에 없는 값은 지어내지 않는다.
            - 조회할 도구가 없는 정보는 아직 조회할 수 없는 정보라고 말한다.
            - resultCategory가 NO_DATA면 해당 데이터가 없다고 말한다.
            - 도구가 error를 돌려주면 그 정보는 확인하지 못했다고 말하고, 데이터가 없다고 바꿔 말하지 않는다.
            - 객체 ID는 "참고 객체 ID"에 있는 값만 쓴다.
            - Action 도구(조회 도구가 아닌 것)는 사용자가 그 일을 해 달라고 분명히 요청했을 때만 부른다.
            - Action 도구가 CONFIRMATION_REQUIRED를 돌려주면 실행하지 않았다고 말하고 실행할지 묻는다.
            - Action 도구가 BLOCKED나 NOT_RUN을 돌려주면 실행되지 않았다고 말하고 이유를 그대로 전한다.
            """;

    /**
     * GMS의 gpt-6-luna는 함수 도구와 추론 옵션을 함께 보내면 400으로 거절한다. 도구를 쓰는 이 요청에만 추론을 끈다고 명시한다(배포 확인
     * 2026-11-06). 도구가 없는 질문 해석 요청(QuestionInterpreter)에는 넣지 않는다.
     */
    static final String NO_REASONING = "none";

    private final ObjectProvider<ChatModel> chatModels;
    private final AuditExecutionQueryService executions;
    private final AgentActionTools actionTools;

    /** AI 호출이 실패하거나 답이 비면 예외를 던진다. 부르는 쪽이 답변을 실패로 끝낸다. */
    public String answer(
            String question, QuestionInterpretation interpretation, AgentQuerySession session) {
        String content =
                ChatClient.create(chatModels.getObject())
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt(question, interpretation))
                        .options(OpenAiChatOptions.builder().reasoningEffort(NO_REASONING))
                        .tools(
                                new AgentQueryTools(session, executions),
                                actionTools.forAnswer(session.asker(), session.questionId()))
                        .call()
                        .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI 답변이 비어 있습니다.");
        }
        return content.strip();
    }

    private static String userPrompt(String question, QuestionInterpretation interpretation) {
        return """
                확인한 조건: 목적=%s, 대상=%s, 기간=%s, 참고 객체 ID=%s
                질문: %s"""
                .formatted(
                        interpretation.purpose(),
                        interpretation.targets(),
                        interpretation.hasPeriod()
                                ? interpretation.from() + " ~ " + interpretation.to()
                                : "없음",
                        interpretation.referenceObjectIds(),
                        question);
    }
}
