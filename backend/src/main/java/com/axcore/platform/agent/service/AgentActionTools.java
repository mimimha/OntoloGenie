package com.axcore.platform.agent.service;

import com.axcore.platform.action.service.ActionCatalogService;
import com.axcore.platform.action.service.ActionExecutionService;
import com.axcore.platform.global.security.LoginAccount;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 질문한 사용자가 지금 실행할 수 있는 Action만 AI 도구로 만든다(144). 목록은 {@link ActionCatalogService}가 정한다(업무 Action, 사용
 * 중, Handler 배포됨, 권한 있음). 권한이 없으면 빈 목록이라 Action 도구를 주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentActionTools {
    /** OpenAI 호환 도구 이름 규칙. 맞지 않는 코드는 도구로 주지 않는다. */
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final ActionCatalogService catalog;
    private final ActionExecutionService executions;
    private final JsonMapper json;

    public List<ToolCallback> forAnswer(LoginAccount asker, long questionId) {
        return catalog.executableFor(asker).stream()
                .filter(
                        action -> {
                            boolean valid = TOOL_NAME.matcher(action.code()).matches();
                            if (!valid) {
                                log.warn("도구 이름으로 쓸 수 없는 Action 코드라 건너뜁니다: {}", action.code());
                            }
                            return valid;
                        })
                .map(
                        action ->
                                (ToolCallback)
                                        new AgentActionTool(
                                                action, asker, questionId, executions, json))
                .toList();
    }
}
