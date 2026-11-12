package com.axcore.platform.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Handler 구현 요청 메시지 형식과 설정이 없을 때·보내다 실패할 때의 동작(Jira 288), 정의 화면 링크(317). */
class HandlerRequestNotifierTests {

    @Test
    void skipsWithoutSchedulingWhenWebhookIsNotConfigured() {
        MattermostWebhookClient client = mock(MattermostWebhookClient.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        when(client.isConfigured()).thenReturn(false);

        new HandlerRequestNotifier(client, executor, properties(null))
                .onRequested(event("A", "B", null));

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
                                new HandlerRequestNotifier(
                                                client, new SyncTaskExecutor(), properties(null))
                                        .onRequested(event("CODE", "이름", null)))
                .doesNotThrowAnyException();
        verify(client).send(anyString(), anyString());
    }

    @Test
    void keepsAdminInputAsCodeSoMarkdownAndMentionsDoNotApply() {
        String text =
                HandlerRequestNotifier.message(
                        event("CODE_1", "@channel `긴급`\n# 제목", Map.of("type", "object")), null);

        assertThat(text)
                .startsWith("#### Handler 구현 요청: `CODE_1`")
                .contains("- Action: `CODE_1` `@channel '긴급' # 제목`")
                .doesNotContain("\n# 제목")
                .contains("- 대상: `SYSTEM`\n")
                .contains("- 입력: 없음")
                .contains("- 사용 여부: 사용");
    }

    @Test
    void includesDescriptionUpToFiveHundredCharactersAndManualJiraGuide() {
        String longDescription = "가".repeat(499) + "나다@channel";

        String text =
                HandlerRequestNotifier.message(
                        event("CODE_2", "이름", longDescription, Map.of("type", "object")), null);

        assertThat(text)
                .contains("- 설명: `" + "가".repeat(499) + "나…(이하 생략)`")
                .doesNotContain("다@channel")
                .contains("Jira 이슈는 자동으로 만들어지지 않습니다.")
                .contains("`[BE][Action] CODE_2 Handler 구현`")
                .contains("위 관리자에게 확인해 주세요.")
                .contains("- 등록한 관리자: `관리자(admin01)`");
        assertThat(
                        HandlerRequestNotifier.message(
                                event("CODE_3", "이름", " ", Map.of("type", "object")), null))
                .contains("- 설명: `-`");
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
        return event(code, name, "설명", inputSchema);
    }

    private static HandlerRequested event(
            String code, String name, String description, Map<String, Object> inputSchema) {
        return new HandlerRequested(
                HandlerRequested.Reason.CREATED,
                code,
                name,
                description,
                "MISSING",
                "SYSTEM",
                null,
                "PLAN_CREATE",
                inputSchema,
                true,
                "관리자(admin01)",
                "관리자(admin01)");
    }

    @Test
    void handlerChangeShowsRegistrantAndEditorAndDashWhenRegistrantUnknown() {
        HandlerRequested changed =
                new HandlerRequested(
                        HandlerRequested.Reason.HANDLER_CHANGED,
                        "CODE_4",
                        "이름",
                        "설명",
                        "MISSING",
                        "SYSTEM",
                        null,
                        "PLAN_CREATE",
                        Map.of("type", "object"),
                        false,
                        "바꾼 사람(admin02)",
                        null);

        assertThat(HandlerRequestNotifier.message(changed, null))
                .contains("아직 없는 Handler로 바꿨습니다")
                .contains("- 처음 등록한 관리자: `-`")
                .contains("- 이번에 바꾼 관리자: `바꾼 사람(admin02)`")
                .contains("- 사용 여부: 사용 안 함")
                .doesNotContain("- 등록한 관리자:");
    }

    @Test
    void sendsDefinitionScreenLinkWhenScreenBaseUrlIsSet() {
        MattermostWebhookClient client = mock(MattermostWebhookClient.class);
        when(client.isConfigured()).thenReturn(true);

        new HandlerRequestNotifier(
                        client, new SyncTaskExecutor(), properties("https://ontologenie.test/"))
                .onRequested(event("ESTIMATE_COMPLETION", "예상 완료 시각", null));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(client).send(eq("ESTIMATE_COMPLETION"), text.capture());
        assertThat(text.getValue())
                .contains(
                        "- 정의 화면: [정의 화면 열기]"
                                + "(https://ontologenie.test/admin/actions/ESTIMATE_COMPLETION)"
                                + " (관리자 계정으로 열림)");
    }

    @Test
    void sendsWithoutLinkWhenScreenBaseUrlIsBlankOrNotWebAddress() {
        for (String base :
                new String[] {
                    null,
                    " ",
                    "ontologenie.test",
                    "ftp://ontologenie.test",
                    "https://",
                    "https://a.test/?x=1"
                }) {
            MattermostWebhookClient client = mock(MattermostWebhookClient.class);
            when(client.isConfigured()).thenReturn(true);

            new HandlerRequestNotifier(client, new SyncTaskExecutor(), properties(base))
                    .onRequested(event("CODE", "이름", null));

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(client).send(eq("CODE"), text.capture());
            assertThat(text.getValue()).as(String.valueOf(base)).doesNotContain("정의 화면");
        }
    }

    @Test
    void encodesActionCodeAndKeepsBasePath() {
        assertThat(properties("http://host.test:8080/app//").definitionLink("관리 작업/A B"))
                .contains(
                        "http://host.test:8080/app/admin/actions/"
                                + "%EA%B4%80%EB%A6%AC%20%EC%9E%91%EC%97%85%2FA%20B");
    }

    private static ActionNotifyProperties properties(String screenBaseUrl) {
        return new ActionNotifyProperties("http://hook.test/hooks/x", screenBaseUrl, null, null);
    }
}
