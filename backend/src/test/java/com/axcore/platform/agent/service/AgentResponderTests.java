package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
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

    @Test
    void turnsOffReasoningOnlyForToolRequest() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("gpt-6-luna").build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("실행 이력이 없습니다.")))));
        AgentActionTools actionTools = mock(AgentActionTools.class);
        when(actionTools.forAnswer(ASKER, 11L)).thenReturn(List.of());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", chatModel);
        AgentResponder responder =
                new AgentResponder(
                        beans.getBeanProvider(ChatModel.class),
                        mock(AuditExecutionQueryService.class),
                        actionTools);

        String answer =
                responder.answer(
                        "PR-03의 상태를 알려줘",
                        new QuestionInterpretation(
                                "상태", List.of("PR-03"), null, null, List.of(), null),
                        new AgentQuerySession(
                                ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC()));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(answer).isEqualTo("실행 이력이 없습니다.");
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getReasoningEffort()).isEqualTo(AgentResponder.NO_REASONING);
        assertThat(options.getModel()).isEqualTo("gpt-6-luna");
        assertThat(((ToolCallingChatOptions) options).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(AgentQueryTools.OBJECT_EXECUTIONS);
    }
}
