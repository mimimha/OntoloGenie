package com.axcore.platform.action.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Handler 구현 요청을 보낼 Mattermost Incoming Webhook(Action 정의서 4-3). 주소는 비밀값이라 환경 변수
 * AXCORE_ACTION_NOTIFY_WEBHOOK_URL로만 넣고 toString·로그에 남기지 않는다.
 */
@ConfigurationProperties("axcore.action-notify")
public record ActionNotifyProperties(
        String webhookUrl, Duration connectTimeout, Duration readTimeout) {

    public ActionNotifyProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public String toString() {
        return "ActionNotifyProperties[configured=%s, connectTimeout=%s, readTimeout=%s]"
                .formatted(isConfigured(), connectTimeout, readTimeout);
    }
}
