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
        assertThat(options.getReasoningEffort()).isEqualTo(AgentResponder.NO_REASONING);
        assertThat(options.getModel()).isEqualTo("gpt-6-luna");
        assertThat(((ToolCallingChatOptions) options).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(AgentQueryTools.OBJECT_EXECUTIONS);
    }

    @Test
    void tellsCurrentTimeInUserZone() {
        Prompt prompt = askAboutPr03();

        // AI는 지금 시각을 모르므로 한국 시각으로 알려 준다. "지금"을 0시로 지어내지 않게 한다(160).
        assertThat(prompt.getUserMessage().getText()).contains("현재 시각: 2026-11-09T14:40:12+09:00");
    }

    private Prompt askAboutPr03() {
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("gpt-6-luna").build());
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
                        CLOCK);

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
