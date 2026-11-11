package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AgentResponderTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T05:40:12Z"), ZoneOffset.UTC);

    private final ChatModel chatModel = mock(ChatModel.class);
    private String answer;

    @Test
    void turnsOffReasoningOnlyForToolRequest() {
        Prompt prompt = askAboutPr03();

        assertThat(answer).isEqualTo("실행 이력이 없습니다.");
        assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        // GMS chat/completions는 도구와 추론을 함께 쓰는 것을 거절하므로 기본값으로 추론을 끈다(설정
        // axcore.agent.reasoning-effort, 147).
        assertThat(options.getReasoningEffort()).isEqualTo("none");
        assertThat(options.getModel()).isEqualTo("gpt-5.6-sol");
        assertThat(((ToolCallingChatOptions) options).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(AgentQueryTools.OBJECT_EXECUTIONS);
    }

    @Test
    void tellsCurrentTimeInUserZone() {
        Prompt prompt = askAboutPr03();

        // AI는 지금 시각을 모르므로 한국 시각으로 알려 준다. "지금"을 0시로 지어내지 않게 한다(160).
        assertThat(prompt.getUserMessage().getText()).contains("현재 시각: 2026-11-09T14:40:12+09:00");
        // 계획의 기준 시점은 현재 시각보다 조회한 데이터의 시점을 먼저 쓰게 한다(160).
        assertThat(prompt.getSystemMessage().getText())
                .contains("기준 시점(dataAsOf)은 계획에 쓴 조회 결과의 기준 시점 중 가장 이른 값");
        // 조회할 도구가 없는 정보를 다른 도구 결과로 "없다"고 답하지 않게 한다(147).
        assertThat(prompt.getSystemMessage().getText())
                .contains("\"참고 객체 ID\"가 비어 있으면 객체 하나를 조회하는 도구는 부르지 않는다")
                .contains("\"아직 조회할 수 없다\"고 말한다");
    }

    @Test
    void sendsConfiguredReasoningEffortForOtherModels() {
        // 받는 값이 모델마다 달라 설정으로 바꿀 수 있어야 한다.
        Prompt prompt = askAboutPr03("low");

        assertThat(((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void leavesReasoningToModelWhenSettingIsBlank() {
        Prompt prompt = askAboutPr03("");

        assertThat(((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).isNull();
    }

    private Prompt askAboutPr03() {
        return askAboutPr03("none");
    }

    private Prompt askAboutPr03(String reasoningEffort) {
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("gpt-5.6-sol").build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("실행 이력이 없습니다.")))));
        AgentActionTools actionTools = mock(AgentActionTools.class);
        when(actionTools.forAnswer(any(AgentQuerySession.class))).thenReturn(List.of());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", chatModel);
        AgentResponder responder =
                new AgentResponder(
                        beans.getBeanProvider(ChatModel.class),
                        mock(AuditExecutionQueryService.class),
                        actionTools,
                        CLOCK,
                        reasoningEffort);

        answer =
                responder.answer(
                        "PR-03의 상태를 알려줘",
                        new QuestionInterpretation(
                                "상태", List.of("PR-03"), null, null, List.of(), null),
                        new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), CLOCK));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return prompt.getValue();
    }
}
