package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.service.ActionCatalogService;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentActionToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final ActionCatalogService catalog = mock(ActionCatalogService.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());
    private final AgentActionTools tools =
            new AgentActionTools(
                    catalog, mock(ActionExecutionService.class), JsonMapper.builder().build());

    @Test
    void givesOnlyActionsFromCatalogWithValidToolNames() {
        when(catalog.executableFor(ASKER))
                .thenReturn(
                        List.of(
                                new ExecutableAction(
                                        "CREATE_REASSIGNMENT_PLAN",
                                        "재배정 계획 생성",
                                        null,
                                        Map.of(),
                                        false),
                                new ExecutableAction("재배정 계획", "이름 규칙 위반", null, Map.of(), false)));

        assertThat(tools.forAnswer(session))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("CREATE_REASSIGNMENT_PLAN");
    }

    @Test
    void givesNoActionToolWithoutPermission() {
        when(catalog.executableFor(ASKER)).thenReturn(List.of());

        assertThat(tools.forAnswer(session)).isEmpty();
    }
}
