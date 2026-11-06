package com.axcore.platform.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Handler 구현 요청 메시지 형식과 설정이 없을 때·보내다 실패할 때의 동작(Jira 288). */
class HandlerRequestNotifierTests {

    @Test
    void skipsWithoutSchedulingWhenWebhookIsNotConfigured() {
        MattermostWebhookClient client = mock(MattermostWebhookClient.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        when(client.isConfigured()).thenReturn(false);

        new HandlerRequestNotifier(client, executor).onRequested(event("A", "B", null));

        verify(executor, never()).execute(any());
        verify(client, never()).send(anyString(), anyString());
    }

    @Test
    void swallowsUnexpectedSendFailure() {
        MattermostWebhookClient client = mock(MattermostWebhookClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.send(anyString(), anyString())).thenThrow(new IllegalStateException("x"));

        assertThatCode(
                        () ->
                                new HandlerRequestNotifier(client, new SyncTaskExecutor())
                                        .onRequested(event("CODE", "이름", null)))
                .doesNotThrowAnyException();
        verify(client).send(anyString(), anyString());
    }

    @Test
    void keepsAdminInputAsCodeSoMarkdownAndMentionsDoNotApply() {
        String text =
                HandlerRequestNotifier.message(
                        event("CODE_1", "@channel `긴급`\n# 제목", Map.of("type", "object")));

        assertThat(text)
                .startsWith("#### Handler 구현 요청: `CODE_1`")
                .contains("- Action: `CODE_1` `@channel '긴급' # 제목`")
                .doesNotContain("\n# 제목")
                .contains("- 대상: `SYSTEM`\n")
                .contains("- 입력: 없음")
                .contains("- 사용 여부: 사용");
    }

    @Test
    void summarizesAtMostTenInputFields() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            properties.put("f" + i, Map.of("type", i == 1 ? "integer" : "string"));
        }
        properties.put("noType", Map.of());
        Map<String, Object> schema =
                Map.of("type", "object", "properties", properties, "required", List.of("f1"));

        String summary = HandlerRequestNotifier.inputSummary(schema);

        assertThat(summary)
                .startsWith("`f1`(`integer`, 필수), `f2`(`string`)")
                .contains("`f10`(`string`)")
                .doesNotContain("`f11`")
                .endsWith(" 외 3개");
        assertThat(HandlerRequestNotifier.inputSummary(Map.of("properties", Map.of("x", Map.of()))))
                .isEqualTo("`x`(형식 미지정)");
    }

    private static HandlerRequested event(
            String code, String name, Map<String, Object> inputSchema) {
        return new HandlerRequested(
                HandlerRequested.Reason.CREATED,
                code,
                name,
                "MISSING",
                "SYSTEM",
                null,
                "PLAN_CREATE",
                inputSchema,
                true,
                "admin01");
    }
}
