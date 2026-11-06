package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void describesActionAsTool() {
        AgentActionTool tool = new AgentActionTool(PLAN, ASKER, 11L, executions, json);

        assertThat(tool.getToolDefinition().name()).isEqualTo("CREATE_REASSIGNMENT_PLAN");
        assertThat(tool.getToolDefinition().description()).startsWith("재배정 계획 생성: ");
        assertThat(tool.getToolDefinition().inputSchema()).contains("fromEquipmentId");
    }

    @Test
    void runsThroughExecutorAsAgentWithQuestionMessage() {
        ActionExecutionResponse response = mock(ActionExecutionResponse.class);
        when(response.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(response.result()).thenReturn(Map.of("planId", 3));
        when(executions.execute(any())).thenReturn(response);

        String reply =
                new AgentActionTool(PLAN, ASKER, 11L, executions, json)
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
    }

    @Test
    void doesNotRunActionNeedingConfirmation() {
        ExecutableAction confirmed =
                new ExecutableAction("CLOSE_ORDER", "작업지시 종료", null, Map.of(), true);

        String reply = new AgentActionTool(confirmed, ASKER, 11L, executions, json).call("{}");

        assertThat(reply).contains(AgentActionTool.CONFIRMATION_REQUIRED);
        verifyNoInteractions(executions);
    }

    @Test
    void reportsUnreadableInputWithoutRunning() {
        String reply = new AgentActionTool(PLAN, ASKER, 11L, executions, json).call("입력 아님");

        assertThat(reply).contains(AgentActionTool.NOT_RUN);
        verifyNoInteractions(executions);
    }

    @Test
    void reportsExecutorErrorAsNotRun() {
        when(executions.execute(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));

        String reply = new AgentActionTool(PLAN, ASKER, 11L, executions, json).call("{}");

        assertThat(reply).contains(AgentActionTool.NOT_RUN);
    }
}
