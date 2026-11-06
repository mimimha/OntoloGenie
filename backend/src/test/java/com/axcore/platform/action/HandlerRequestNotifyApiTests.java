package com.axcore.platform.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler 구현 요청 알림(Jira 288, Action 정의서 4-3). 가짜 Mattermost Webhook으로 보낸 횟수·본문과, 알림이 실패해도 088·090
 * 결과와 감사 기록이 그대로인지 확인한다. Webhook 주소의 비밀 경로는 시험용 값이다.
 */
@SpringBootTest(properties = "axcore.action-notify.read-timeout=3s")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, ActionDefinitionApiTests.HandlerConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class HandlerRequestNotifyApiTests {

    private static final String ACTIONS = "/api/v1/actions";
    private static final String DEPLOYED = "TEST_HANDLER";
    private static final String UNDEPLOYED = "UNDEPLOYED_HANDLER";
    private static final String HOOK_SECRET = "hook-secret-" + UUID.randomUUID();
    private static final FakeMattermost MATTERMOST = FakeMattermost.start();

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    LoginAccount admin;

    @DynamicPropertySource
    static void webhook(DynamicPropertyRegistry registry) {
        registry.add(
                "axcore.action-notify.webhook-url",
                () -> MATTERMOST.baseUrl() + "/hooks/" + HOOK_SECRET);
    }

    @AfterAll
    static void stop() {
        MATTERMOST.server.stop(0);
    }

    @BeforeEach
    void fixtures() {
        MATTERMOST.reset();
        String loginId = "notify-admin-" + UUID.randomUUID().toString().substring(0, 8);
        long id =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id,
                                                    must_change_password)
                                values (:login, '알림 관리자', 'test-hash',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", loginId)
                        .query(Long.class)
                        .single();
        admin = new LoginAccount(id, loginId, "ADMIN", false);
    }

    @Test
    void sendsOneRequestAfterRegisteringDefinitionWithoutHandler() throws Exception {
        long typeId = objectType();
        String code = uniqueCode();

        write(
                        post(ACTIONS),
                        """
                        {"code": "%s", "name": "설비 점검 요청", "description": "설명은 알림에 넣지 않는다",
                         "targetKind": "OBJECT", "targetObjectTypeId": %d, "handlerKey": "%s",
                         "inputSchema": {"type": "object", "required": ["reason"],
                                         "properties": {"reason": {"type": "string"},
                                                        "dueAt": {"type": "string"}}},
                         "actionKind": "WRITE", "requiresConfirmation": true,
                         "requiredPermission": "PLAN_CREATE"}
                        """
                                .formatted(code, typeId, UNDEPLOYED))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.handlerReady").value(false));

        String text = awaitMessage(code);
        assertThat(text)
                .contains("Handler 구현 요청: `" + code + "`")
                .contains("등록했습니다")
                .contains("`설비 점검 요청`")
                .contains("handlerKey: `" + UNDEPLOYED + "`")
                .contains("`OBJECT` / 객체 유형 `EQ_")
                .contains("(테스트 설비)")
                .contains("필요 권한: `PLAN_CREATE`")
                .contains("`reason`(`string`, 필수)")
                .contains("`dueAt`(`string`)")
                .contains("`" + admin.loginId() + "`")
                .contains("[BE][Action] " + code + " Handler 구현")
                .doesNotContain("설명은 알림에 넣지 않는다");
        assertThat(MATTERMOST.requests).hasSize(1);
        assertThat(MATTERMOST.requests.peek().path()).isEqualTo("/hooks/" + HOOK_SECRET);
        assertThat(MATTERMOST.requests.peek().contentType()).startsWith("application/json");
    }

    @Test
    void sendsOnlyWhenUpdateChangesHandlerKeyToMissingHandler() throws Exception {
        String deployed = uniqueCode();
        create(deployed, DEPLOYED).andExpect(jsonPath("$.data.handlerReady").value(true));
        String missing = uniqueCode();
        create(missing, UNDEPLOYED);
        awaitMessage(missing);
        MATTERMOST.requests.clear();

        // Handler가 있는 키로 바꾸거나, 다른 필드만 바꾸거나, 같은 키를 다시 보내면 보내지 않는다. 조회도 보내지 않는다.
        update(missing, "{\"handlerKey\": \"%s\", \"rowVersion\": 0}".formatted(DEPLOYED))
                .andExpect(jsonPath("$.data.handlerReady").value(true));
        update(deployed, "{\"name\": \"이름만 바꿈\", \"rowVersion\": 0}");
        update(
                deployed,
                "{\"handlerKey\": \"%s\", \"rowVersion\": %d}"
                        .formatted(DEPLOYED, rowVersion(deployed)));
        mvc.perform(get(ACTIONS + "/" + missing).with(authentication(admin.toAuthentication())))
                .andExpect(status().isOk());
        mvc.perform(get(ACTIONS).with(authentication(admin.toAuthentication())))
                .andExpect(status().isOk());
        // 관리 Action은 090에서 거절되므로 보내지 않는다.
        write(
                        patch(ACTIONS + "/CREATE_ACCOUNT"),
                        "{\"handlerKey\": \"%s\", \"rowVersion\": 0}".formatted(UNDEPLOYED))
                .andExpect(status().is4xxClientError());

        // 없는 키로 바꾸면 보낸다. 이미 없는 키에서 다른 없는 키로 바꿔도 보낸다.
        update(
                        deployed,
                        "{\"handlerKey\": \"%s\", \"rowVersion\": %d}"
                                .formatted(UNDEPLOYED, rowVersion(deployed)))
                .andExpect(jsonPath("$.data.handlerReady").value(false));
        String text = awaitMessage(deployed);
        assertThat(text).contains("handlerKey를 아직 없는 Handler로 바꿨습니다");
        update(
                deployed,
                "{\"handlerKey\": \"OTHER_UNDEPLOYED\", \"rowVersion\": %d}"
                        .formatted(rowVersion(deployed)));
        awaitCount(2);

        assertThat(MATTERMOST.codes()).containsExactly(deployed, deployed);
    }

    @Test
    void doesNotSendWhenRegisteredHandlerExistsOrRequestFails() throws Exception {
        String deployed = uniqueCode();
        create(deployed, DEPLOYED);
        // 같은 코드로 다시 등록하면 409이고 저장되지 않았으므로 보내지 않는다.
        write(post(ACTIONS), body(deployed, UNDEPLOYED)).andExpect(status().isConflict());
        // 없는 권한이면 400이고 보내지 않는다.
        write(post(ACTIONS), body(uniqueCode(), UNDEPLOYED).replace("PLAN_CREATE", "NO_SUCH"))
                .andExpect(status().isBadRequest());

        String marker = uniqueCode();
        create(marker, UNDEPLOYED);
        awaitMessage(marker);

        assertThat(MATTERMOST.codes()).containsExactly(marker);
    }

    @Test
    void webhookFailureKeepsResponseAndAuditAndHidesWebhookAddress(CapturedOutput output)
            throws Exception {
        MATTERMOST.respond(500, 0);
        String code = uniqueCode();

        create(code, UNDEPLOYED).andExpect(jsonPath("$.data.handlerReady").value(false));
        awaitLog(output, "Action " + code + "의 Handler 구현 요청을 Mattermost가 거절했습니다. HTTP 500");

        assertThat(definitionCount(code)).isOne();
        assertThat(lastAuditStatus("CREATE_ACTION_DEFINITION")).isEqualTo("SUCCEEDED");
        assertThat(MATTERMOST.requests).hasSize(1);
        assertThat(output.getAll()).doesNotContain(HOOK_SECRET);
    }

    @Test
    void slowWebhookDoesNotDelayResponseAndTimesOutQuietly(CapturedOutput output) throws Exception {
        // 시간 제한(3초)보다 늦게 답한다. 같은 스레드에서 보냈다면 응답이 3초 넘게 걸린다.
        MATTERMOST.respond(200, 4_000);
        String code = uniqueCode();

        long started = System.nanoTime();
        create(code, UNDEPLOYED);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_500);
        awaitLog(output, "Action " + code + "의 Handler 구현 요청을 Mattermost로 보내지 못했습니다");
        assertThat(definitionCount(code)).isOne();
        assertThat(output.getAll()).doesNotContain(HOOK_SECRET);
    }

    // ---------------------------------------------------------------- 도우미

    private ResultActions create(String code, String handlerKey) throws Exception {
        return write(post(ACTIONS), body(code, handlerKey)).andExpect(status().isCreated());
    }

    private ResultActions update(String code, String body) throws Exception {
        return write(patch(ACTIONS + "/" + code), body).andExpect(status().isOk());
    }

    private ResultActions write(MockHttpServletRequestBuilder request, String body)
            throws Exception {
        return mvc.perform(
                request.with(authentication(admin.toAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String body(String code, String handlerKey) {
        return """
                {"code": "%s", "name": "알림 시험 Action", "description": "알림 시험",
                 "targetKind": "SYSTEM", "handlerKey": "%s",
                 "inputSchema": {"type": "object"}, "actionKind": "WRITE",
                 "requiresConfirmation": false, "requiredPermission": "PLAN_CREATE"}
                """
                .formatted(code, handlerKey);
    }

    private String awaitMessage(String code) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (Received request : MATTERMOST.requests) {
                String text = json.readTree(request.body()).path("text").asString();
                if (text.contains("Handler 구현 요청: `" + code + "`")) {
                    return text;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Mattermost 알림이 오지 않았습니다: " + code);
    }

    private void awaitCount(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (MATTERMOST.requests.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(MATTERMOST.requests).hasSize(count);
    }

    private static void awaitLog(CapturedOutput output, String line) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!output.getAll().contains(line) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(output.getAll()).contains(line);
    }

    private long objectType() {
        return jdbc.sql(
                        "insert into object_type (code, name) values (:code, '테스트 설비') returning id")
                .param("code", "EQ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .query(Long.class)
                .single();
    }

    private long definitionCount(String code) {
        return jdbc.sql("select count(*) from action_definition where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private int rowVersion(String code) {
        return jdbc.sql("select row_version from action_definition where code = :code")
                .param("code", code)
                .query(Integer.class)
                .single();
    }

    private String lastAuditStatus(String actionCode) {
        return jdbc.sql(
                        """
                        select e.status from action_execution e
                        join action_definition d on d.id = e.action_definition_id
                        where d.code = :code and e.actor_id = :actorId
                        order by e.id desc limit 1
                        """)
                .param("code", actionCode)
                .param("actorId", admin.id())
                .query(String.class)
                .single();
    }

    private static String uniqueCode() {
        return "NOTIFY_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Incoming Webhook을 흉내 낸다. 받은 요청을 쌓고 정해 둔 상태 코드·지연으로 답한다. */
    static final class FakeMattermost {
        final HttpServer server;
        final Queue<Received> requests = new ConcurrentLinkedQueue<>();
        volatile int status;
        volatile long delayMillis;
        private final JsonMapper json = JsonMapper.builder().build();

        private FakeMattermost(HttpServer server) {
            this.server = server;
        }

        static FakeMattermost start() {
            try {
                var fake =
                        new FakeMattermost(
                                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
                fake.server.createContext(
                        "/hooks/",
                        exchange -> {
                            fake.requests.add(
                                    new Received(
                                            exchange.getRequestURI().getPath(),
                                            exchange.getRequestHeaders().getFirst("Content-Type"),
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8)));
                            try {
                                Thread.sleep(fake.delayMillis);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
                            try {
                                exchange.sendResponseHeaders(fake.status, bytes.length);
                                exchange.getResponseBody().write(bytes);
                            } catch (IOException ignored) {
                                // 시간 제한으로 끊긴 연결
                            }
                            exchange.close();
                        });
                fake.server.setExecutor(Executors.newCachedThreadPool());
                fake.server.start();
                fake.reset();
                return fake;
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            requests.clear();
            respond(200, 0);
        }

        void respond(int status, long delayMillis) {
            this.status = status;
            this.delayMillis = delayMillis;
        }

        /** 받은 알림의 Action 코드(제목 줄)를 받은 순서대로. */
        List<String> codes() {
            return requests.stream()
                    .map(r -> json.readTree(r.body()).path("text").asString())
                    .map(text -> text.replaceAll("(?s)^#### Handler 구현 요청: `([^`]+)`.*", "$1"))
                    .toList();
        }
    }

    record Received(String path, String contentType, String body) {}
}
