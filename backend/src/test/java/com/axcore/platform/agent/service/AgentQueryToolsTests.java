package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class AgentQueryToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final AuditExecutionQueryService executions = mock(AuditExecutionQueryService.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());
    private final AgentQueryTools tools = new AgentQueryTools(session, executions);

    @Test
    void returnsExecutionsAsFoundWithoutDataAsOf() {
        AuditExecutionResponse row = mock(AuditExecutionResponse.class);
        when(row.id()).thenReturn(5L);
        when(row.actionName()).thenReturn("재배정 계획 생성");
        when(row.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(row.requestedAt()).thenReturn(Instant.parse("2026-11-01T00:00:00Z"));
        when(executions.objectExecutions(eq(ASKER), eq(301L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        Map<String, Object> reply = tools.objectExecutions(301L);

        assertThat(reply).containsEntry("resultCategory", "FOUND");
        assertThat((List<?>) reply.get("data")).hasSize(1);
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentQueryTools.OBJECT_EXECUTIONS);
        assertThat(result.sources()).containsExactly(Map.of("objectId", 301L));
        assertThat(result.dataAsOf()).isNull();
    }

    @Test
    void treatsMissingObjectAsNoData() {
        when(executions.objectExecutions(eq(ASKER), eq(999L), any(), any()))
                .thenThrow(
                        new BusinessException(
                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                Map.of("resource", "object_instance")));

        assertThat(tools.objectExecutions(999L)).containsEntry("resultCategory", "NO_DATA");
        assertThat(session.results().getFirst().resultCategory())
                .isEqualTo(AgentResultCategory.NO_DATA);
    }

    @Test
    void reportsOtherErrorsAsFailed() {
        when(executions.objectExecutions(eq(ASKER), eq(301L), any(), any()))
                .thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThat(tools.objectExecutions(301L)).containsKey("error");
        assertThat(session.results().getFirst().succeeded()).isFalse();
    }
}
