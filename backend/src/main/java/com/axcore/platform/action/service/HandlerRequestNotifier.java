package com.axcore.platform.action.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 정의 저장이 커밋된 뒤 Mattermost 팀 채널로 개발자에게 Handler 구현을 요청한다(Action 정의서 4-3). 다른 스레드에서 보내 088·090 응답을 붙잡지
 * 않고, 실패해도 등록·수정 결과와 감사 기록은 그대로다. Jira 이슈는 알림을 받은 개발자가 만든다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class HandlerRequestNotifier {

    private static final int MAX_FIELD_LENGTH = 200;
    private static final int MAX_INPUT_FIELDS = 10;

    private final MattermostWebhookClient client;
    private final TaskExecutor applicationTaskExecutor;

    @TransactionalEventListener
    public void onRequested(HandlerRequested event) {
        if (!client.isConfigured()) {
            return;
        }
        try {
            applicationTaskExecutor.execute(() -> send(event));
        } catch (RuntimeException ex) {
            log.warn(
                    "Action {}의 Handler 구현 요청을 보낼 작업을 넣지 못했습니다: {}",
                    event.code(),
                    ex.getClass().getSimpleName());
        }
    }

    private void send(HandlerRequested event) {
        try {
            client.send(event.code(), message(event));
        } catch (RuntimeException ex) {
            // 예외 메시지에 Webhook 주소가 들어갈 수 있어 종류만 남긴다.
            log.warn(
                    "Action {}의 Handler 구현 요청 중 예상하지 못한 오류가 났습니다: {}",
                    event.code(),
                    ex.getClass().getSimpleName());
        }
    }

    /** 관리자가 입력한 값은 코드 서식으로 감싸 Mattermost 서식·멘션(@channel 등)으로 해석되지 않게 한다. 설명은 넣지 않는다. */
    static String message(HandlerRequested event) {
        String reason =
                event.reason() == HandlerRequested.Reason.CREATED
                        ? "관리자가 Handler가 없는 업무 Action을 등록했습니다."
                        : "관리자가 업무 Action의 handlerKey를 아직 없는 Handler로 바꿨습니다.";
        List<String> lines = new ArrayList<>();
        lines.add("#### Handler 구현 요청: " + literal(event.code()));
        lines.add(reason + " Handler가 배포되기 전까지 이 Action은 실행되지 않습니다(BLOCKED).");
        lines.add("");
        lines.add("- Action: " + literal(event.code()) + " " + literal(event.name()));
        lines.add("- handlerKey: " + literal(event.handlerKey()));
        lines.add(
                "- 대상: "
                        + literal(event.targetKind())
                        + (event.objectType() == null
                                ? ""
                                : " / 객체 유형 " + literal(event.objectType())));
        lines.add("- 필요 권한: " + literal(event.requiredPermission()));
        lines.add("- 입력: " + inputSummary(event.inputSchema()));
        lines.add("- 사용 여부: " + (event.active() ? "사용" : "사용 안 함"));
        lines.add("- 등록·수정한 관리자: " + literal(event.adminLoginId()));
        lines.add("");
        lines.add(
                "Jira 이슈 "
                        + literal("[BE][Action] " + event.code() + " Handler 구현")
                        + "을 만들고, key()가 "
                        + literal(event.handlerKey())
                        + "인 ActionHandler를 배포해 주세요.");
        return String.join("\n", lines);
    }

    /** inputSchema의 속성 이름·형식·필수 여부. 값의 세부 규칙은 정의 조회(089)에서 본다. */
    static String inputSummary(Map<String, Object> inputSchema) {
        if (inputSchema == null
                || !(inputSchema.get("properties") instanceof Map<?, ?> properties)
                || properties.isEmpty()) {
            return "없음";
        }
        Collection<?> required =
                inputSchema.get("required") instanceof Collection<?> names ? names : List.of();
        List<String> fields = new ArrayList<>();
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            if (fields.size() == MAX_INPUT_FIELDS) {
                break;
            }
            String name = String.valueOf(property.getKey());
            Object type = property.getValue() instanceof Map<?, ?> rule ? rule.get("type") : null;
            fields.add(
                    literal(name)
                            + "("
                            + (type == null ? "형식 미지정" : literal(String.valueOf(type)))
                            + (required.contains(name) ? ", 필수" : "")
                            + ")");
        }
        String summary = String.join(", ", fields);
        int rest = properties.size() - fields.size();
        return rest > 0 ? summary + " 외 " + rest + "개" : summary;
    }

    private static String literal(String value) {
        if (value == null) {
            return "`-`";
        }
        String flat = value.replace('`', '\'').replaceAll("\\s+", " ").strip();
        if (flat.length() > MAX_FIELD_LENGTH) {
            flat = flat.substring(0, MAX_FIELD_LENGTH) + "…";
        }
        return "`" + flat + "`";
    }
}
