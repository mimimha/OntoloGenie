package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;

/**
 * AI에게 주는 조회 도구(144). 답변 하나마다 새로 만든다. 각 도구는 기존 조회 API의 Service를 질문한 사용자로 불러 권한 규칙을 그대로 지키고, 호출은
 * {@link AgentQuerySession}이 기록한다. 객체 검색·상세·관계 도구는 S15P31C106-136~138이 끝나면 더한다.
 */
public class AgentQueryTools {
    static final String OBJECT_EXECUTIONS = "OBJECT_EXECUTIONS";
    static final int MAX_EXECUTIONS = 20;

    private static final String OBJECT_INSTANCE = "object_instance";
    private static final ObjectExecutionSearchRequest ALL_EXECUTIONS =
            new ObjectExecutionSearchRequest(null, null, null, null, null);

    private final AgentQuerySession session;
    private final AuditExecutionQueryService executions;

    public AgentQueryTools(AgentQuerySession session, AuditExecutionQueryService executions) {
        this.session = session;
        this.executions = executions;
    }

    /** 실행 이력 한 줄. 입력·결과 원문은 AI에게 넘기지 않는다. */
    public record ExecutionSummary(
            Long id, String actionName, ExecutionStatus status, Instant requestedAt) {

        static ExecutionSummary from(AuditExecutionResponse response) {
            return new ExecutionSummary(
                    response.id(),
                    response.actionName(),
                    response.status(),
                    response.requestedAt());
        }
    }

    /**
     * API 080. 플랫폼이 직접 남긴 실행 기록이라 원천 수집 시각이 없어 기준 시점(dataAsOf)은 null이다. ADMIN이 아니면 본인이 실행한 기록만 보인다.
     */
    @Tool(
            name = OBJECT_EXECUTIONS,
            description =
                    "객체에 대해 실행된 Action·작업 이력을 최근 순으로 최대 20건 조회한다. 객체 ID는 질문의 참고 객체에 있는 값만 쓴다.")
    public Map<String, Object> objectExecutions(@ToolParam(description = "객체 ID") long objectId) {
        return session.run(
                OBJECT_EXECUTIONS,
                "실행 이력",
                Map.of("objectId", objectId),
                OBJECT_INSTANCE,
                objectId,
                () -> {
                    List<Map<String, Object>> sources = List.of(Map.of("objectId", objectId));
                    try {
                        List<ExecutionSummary> rows =
                                executions
                                        .objectExecutions(
                                                session.asker(),
                                                objectId,
                                                ALL_EXECUTIONS,
                                                PageRequest.of(0, MAX_EXECUTIONS))
                                        .map(ExecutionSummary::from)
                                        .getContent();
                        return new AgentQuerySession.Outcome(
                                rows,
                                rows.isEmpty()
                                        ? AgentResultCategory.NO_DATA
                                        : AgentResultCategory.FOUND,
                                sources,
                                null);
                    } catch (BusinessException ex) {
                        if (ex.getErrorCode() != CommonErrorCode.RESOURCE_NOT_FOUND) {
                            throw ex;
                        }
                        return new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.NO_DATA, List.of(), null);
                    }
                });
    }
}
