package com.axcore.platform.action.service;

import java.net.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mattermost Incoming Webhook에 메시지를 한 번 보낸다(Action 정의서 4-3). Webhook 주소는 그 자체가 비밀값이다. 예외 메시지에 주소가
 * 들어갈 수 있어 실패 로그에는 예외 종류와 HTTP 상태만 남긴다. 다시 보내지 않는다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ActionNotifyProperties.class)
public class MattermostWebhookClient {

    private final ActionNotifyProperties properties;
    private final JsonMapper json;
    private final RestClient rest;

    public MattermostWebhookClient(ActionNotifyProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(properties.connectTimeout())
                                .build());
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).build();
        if (!properties.isConfigured()) {
            log.info(
                    "AXCORE_ACTION_NOTIFY_WEBHOOK_URL이 비어 있어 Handler 구현 요청을 Mattermost로 보내지 않습니다.");
        }
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 보냈으면 true. 실패하면 로그만 남기고 false를 돌려준다. */
    public boolean send(String actionCode, String text) {
        try {
            rest.post()
                    .uri(properties.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(Map.of("text", text)))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Action {}의 Handler 구현 요청을 Mattermost로 보냈습니다.", actionCode);
            return true;
        } catch (RestClientResponseException ex) {
            log.warn(
                    "Action {}의 Handler 구현 요청을 Mattermost가 거절했습니다. HTTP {}",
                    actionCode,
                    ex.getStatusCode().value());
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn(
                    "Action {}의 Handler 구현 요청을 Mattermost로 보내지 못했습니다: {}",
                    actionCode,
                    ex.getClass().getSimpleName());
        }
        return false;
    }
}
