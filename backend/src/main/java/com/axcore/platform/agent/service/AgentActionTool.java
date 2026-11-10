package com.axcore.platform.agent.service;

import com.axcore.platform.action.dto.ActionExecutionResponse;
import com.axcore.platform.action.service.ActionExecutionCommand;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.action.service.ExecutableAction;
import com.axcore.platform.audit.domain.EntryPoint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업무 Action 하나를 AI 도구로 감싼다(144, Action 정의서 7절). 실행은 091 HTTP가 아니라 {@link ActionExecutionService}를
 * 서버 안에서 불러 entryPoint=AGENT, agentMessageId=질문 메시지로 남긴다. 실행 기록(actorType=AGENT)은 실행기가 남긴다. 결과는 답변의
 * answerMeta.actions에도 남겨 화면이 성공·차단과 만든 계획을 보여 준다(160).
 *
 * <p>실행 전에 사용자 확인이 필요한 Action(requiresConfirmation)은 실행하지 않고 확인이 필요하다고만 AI에게 알린다. 확인 뒤 실행하는 흐름은 재배정
 * 단계(S15P31C106-159~161)에서 만든다. 확인 전에 실행기를 부르면 BLOCKED가 기록되고 같은 requestId로는 다시 실행할 수 없기 때문이다.
 */
@Slf4j
public class AgentActionTool implements ToolCallback {
    static final String CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED";
    static final String NOT_RUN = "NOT_RUN";

    private static final TypeReference<Map<String, Object>> INPUT_TYPE = new TypeReference<>() {};
    private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final ExecutableAction action;
    private final AgentQuerySession session;
    private final ActionExecutionService executions;
    private final JsonMapper json;
    private final ToolDefinition definition;

    public AgentActionTool(
            ExecutableAction action,
            AgentQuerySession session,
            ActionExecutionService executions,
            JsonMapper json) {
        this.action = action;
        this.session = session;
        this.executions = executions;
        this.json = json;
        this.definition =
                ToolDefinition.builder()
                        .name(action.code())
                        .description(description(action))
                        .inputSchema(schema(action, json))
                        .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        if (action.requiresConfirmation()) {
            return notRun(CONFIRMATION_REQUIRED, "실행 전에 사용자 확인이 필요해 실행하지 않았습니다. 사용자에게 실행할지 물어보세요.");
        }
        Map<String, Object> input;
        try {
            input = json.readValue(toolInput == null ? "{}" : toolInput, INPUT_TYPE);
        } catch (JacksonException ex) {
            return notRun(NOT_RUN, "입력을 읽지 못해 실행하지 않았습니다.");
        }
        ActionExecutionResponse response;
        try {
            response =
                    executions.execute(
                            new ActionExecutionCommand(
                                    session.asker(),
                                    action.code(),
                                    UUID.randomUUID(),
                                    null,
                                    null,
                                    input,
                                    false,
                                    EntryPoint.AGENT,
                                    session.questionId()));
        } catch (RuntimeException ex) {
            log.error("Agent Action {} 실행 실패 question={}", action.code(), session.questionId(), ex);
            return notRun(NOT_RUN, "실행하지 못했습니다.");
        }
        // 실행기가 결과를 돌려준 뒤에는 "실행하지 못함"으로 바꾸지 않는다. 실제로 실행된 Action을 한 번만 남긴다.
        session.addAction(
                new AgentActionOutcome(
                        action.code(),
                        action.name(),
                        response.status().name(),
                        response.id(),
                        response.result(),
                        response.blockedReason()));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("status", response.status().name());
        reply.put("blockedReason", response.blockedReason());
        reply.put("result", response.result());
        return json.writeValueAsString(reply);
    }

    /** 실행기를 부르지 않았다. 답변에도 실행하지 않았다고 남겨, 화면이 성공으로 보여 주지 않게 한다. */
    private String notRun(String status, String message) {
        session.addAction(AgentActionOutcome.notExecuted(action.code(), action.name(), status));
        return json.writeValueAsString(Map.of("status", status, "message", message));
    }

    private static String description(ExecutableAction action) {
        String text =
                action.description() == null
                        ? action.name()
                        : action.name() + ": " + action.description();
        return action.requiresConfirmation() ? text + " (실행 전에 사용자 확인 필요)" : text;
    }

    private static String schema(ExecutableAction action, JsonMapper json) {
        Map<String, Object> schema = action.inputSchema();
        return schema == null || schema.isEmpty()
                ? EMPTY_OBJECT_SCHEMA
                : json.writeValueAsString(schema);
    }
}
