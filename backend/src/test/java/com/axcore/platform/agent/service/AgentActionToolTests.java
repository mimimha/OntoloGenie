package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AgentActionToolTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final ExecutableAction PLAN =
            new ExecutableAction(
                    "CREATE_REASSIGNMENT_PLAN",
                    "재배정 계획 생성",
                    "정지 설비의 작업을 대체 설비로 옮기는 계획을 저장한다.",
                    Map.of("type", "object", "required", List.of("fromEquipmentId")),
                    false);

    private final ActionExecutionService executions = mock(ActionExecutionService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());

    @Test
    void describesActionAsTool() {
        AgentActionTool tool = new AgentActionTool(PLAN, session, executions, json);

        assertThat(tool.getToolDefinition().name()).isEqualTo("CREATE_REASSIGNMENT_PLAN");
        assertThat(tool.getToolDefinition().description()).startsWith("재배정 계획 생성: ");
        assertThat(tool.getToolDefinition().inputSchema()).contains("fromEquipmentId");
    }

    @Test
    void runsThroughExecutorAsAgentWithQuestionMessage() {
        ActionExecutionResponse response = mock(ActionExecutionResponse.class);
        when(response.id()).thenReturn(512L);
        when(response.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(response.result()).thenReturn(Map.of("planId", 3));
        when(executions.execute(any())).thenReturn(response);

        String reply =
                new AgentActionTool(PLAN, session, executions, json)
                        .call("{\"fromEquipmentId\":301}");

        ArgumentCaptor<ActionExecutionCommand> command =
                ArgumentCaptor.forClass(ActionExecutionCommand.class);
        verify(executions).execute(command.capture());
        assertThat(command.getValue().actor()).isEqualTo(ASKER);
        assertThat(command.getValue().code()).isEqualTo("CREATE_REASSIGNMENT_PLAN");
        assertThat(command.getValue().entryPoint()).isEqualTo(EntryPoint.AGENT);
        assertThat(command.getValue().agentMessageId()).isEqualTo(11L);
        assertThat(command.getValue().confirmed()).isFalse();
        assertThat(command.getValue().requestId()).isNotNull();
        assertThat(command.getValue().input()).containsEntry("fromEquipmentId", 301);
        assertThat(reply).contains("SUCCEEDED").contains("planId");
        assertThat(session.actions())
                .containsExactly(
                        new AgentActionOutcome(
                                "CREATE_REASSIGNMENT_PLAN",
                                "재배정 계획 생성",
                                "SUCCEEDED",
                                512L,
                                Map.of("planId", 3),
                                null));
    }

    @Test
    void keepsBlockedReasonForAnswer() {
        ActionExecutionResponse response = mock(ActionExecutionResponse.class);
        when(response.id()).thenReturn(513L);
        when(response.status()).thenReturn(ExecutionStatus.BLOCKED);
        when(response.result()).thenReturn(Map.of());
        when(response.blockedReason()).thenReturn("대체 설비의 공정이 맞지 않습니다.");
        when(executions.execute(any())).thenReturn(response);

        new AgentActionTool(PLAN, session, executions, json).call("{}");

        assertThat(session.actions())
                .containsExactly(
                        new AgentActionOutcome(
                                "CREATE_REASSIGNMENT_PLAN",
                                "재배정 계획 생성",
                                "BLOCKED",
                                513L,
                                Map.of(),
                                "대체 설비의 공정이 맞지 않습니다."));
    }

    @Test
    void doesNotRunActionNeedingConfirmation() {
        ExecutableAction confirmed =
                new ExecutableAction("CLOSE_ORDER", "작업지시 종료", null, Map.of(), true);

        String reply = new AgentActionTool(confirmed, session, executions, json).call("{}");

        assertThat(reply).contains(AgentActionTool.CONFIRMATION_REQUIRED);
        verifyNoInteractions(executions);
        assertThat(session.actions())
                .containsExactly(
                        AgentActionOutcome.notExecuted(
                                "CLOSE_ORDER", "작업지시 종료", AgentActionTool.CONFIRMATION_REQUIRED));
    }

    @Test
    void reportsUnreadableInputWithoutRunning() {
        String reply = new AgentActionTool(PLAN, session, executions, json).call("입력 아님");

        assertThat(reply).contains(AgentActionTool.NOT_RUN);
        verifyNoInteractions(executions);
        assertThat(session.actions())
                .containsExactly(
                        AgentActionOutcome.notExecuted(
                                "CREATE_REASSIGNMENT_PLAN", "재배정 계획 생성", AgentActionTool.NOT_RUN));
    }

    @Test
    void reportsExecutorErrorAsNotRun() {
        when(executions.execute(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));

        String reply = new AgentActionTool(PLAN, session, executions, json).call("{}");

        assertThat(reply).contains(AgentActionTool.NOT_RUN);
        assertThat(session.actions())
                .containsExactly(
                        AgentActionOutcome.notExecuted(
                                "CREATE_REASSIGNMENT_PLAN", "재배정 계획 생성", AgentActionTool.NOT_RUN));
    }

    @Test
    void keepsExecutedActionOnceEvenIfReplyCannotBeWritten() {
        ActionExecutionResponse response = mock(ActionExecutionResponse.class);
        when(response.id()).thenReturn(512L);
        when(response.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(response.result()).thenReturn(Map.of("planId", 3));
        when(executions.execute(any())).thenReturn(response);
        // 실행은 끝났는데 AI에게 돌려줄 응답을 글로 바꾸다 실패하는 경우
        JsonMapper brokenJson = mock(JsonMapper.class);
        when(brokenJson.writeValueAsString(any())).thenThrow(new IllegalStateException("직렬화 실패"));
        ExecutableAction noSchema =
                new ExecutableAction(
                        "CREATE_REASSIGNMENT_PLAN", "재배정 계획 생성", null, Map.of(), false);
        AgentActionTool tool = new AgentActionTool(noSchema, session, executions, brokenJson);

        assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(RuntimeException.class);

        // 실제로 실행된 Action은 "실행하지 못함"으로 한 번 더 남지 않는다.
        assertThat(session.actions())
                .extracting(AgentActionOutcome::status)
                .containsExactly("SUCCEEDED");
    }
}
