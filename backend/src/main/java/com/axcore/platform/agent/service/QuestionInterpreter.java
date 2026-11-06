package com.axcore.platform.agent.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** 질문에서 대상·기간·조회 목적을 뽑는다(142). AI 출력은 그대로 믿지 않고 형식과 범위를 검사한다. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class QuestionInterpreter {
    /** "이번 달" 같은 상대 기간은 사용자 기준 날짜로 계산한다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private static final int MAX_TARGETS = 10;
    private static final int MAX_TEXT_LENGTH = 100;

    private static final String SYSTEM_PROMPT =
            """
            너는 제조 설비·작업 데이터 조회 Agent의 질문 해석기다. 사용자 질문에서 조회 조건만 뽑는다.
            질문과 화면 기간에 없는 값은 지어내지 않는다.
            - purpose: 알고 싶은 것을 짧은 명사구로 쓴다(예: 비가동 이력, 장부가). 알 수 없으면 null.
            - targets: 질문에 나온 설비·작업지시·공정 같은 대상의 이름이나 코드를 그대로 쓴다(예: PR-03). 없으면 빈 배열.
            - from, to: 조회 기간을 yyyy-MM-dd로 쓴다. "이번 달", "최근 7일" 같은 표현은 오늘 날짜로 계산한다.
              질문에 기간이 없고 화면 기간이 있으면 화면 기간을 쓴다. 둘 다 없으면 null.
            - vaguePeriod: "최근", "요즘", "지난번"처럼 기간을 말했지만 범위가 정해지지 않은 표현이면 그 표현을 그대로 쓰고
              from, to는 null로 둔다. 기간을 임의로 정하지 않는다. 그런 표현이 없으면 null.
            "이전에 되물은 내용"이 있으면 질문은 그에 대한 사용자의 답이다. 답에서 새로 알려 준 값만 뽑고, 이미 확인한 조건은 다시 쓰지 않는다.
            """;

    private final ObjectProvider<ChatModel> chatModels;
    private final Clock clock;

    /** GMS 설정이 없어 AI 모델이 만들어지지 않았으면 false. */
    public boolean isConfigured() {
        return chatModels.getIfAvailable() != null;
    }

    /** AI 호출이 실패하거나 응답이 비면 예외를 던진다. 부르는 쪽이 답변을 실패로 끝낸다. pending은 직전 되묻기이며 없으면 null이다. */
    public QuestionInterpretation interpret(
            String question, Map<String, Object> screenContext, PendingClarification pending) {
        Extracted extracted =
                ChatClient.create(chatModels.getObject())
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt(question, screenContext, pending))
                        .call()
                        .entity(Extracted.class);
        if (extracted == null) {
            throw new IllegalStateException("AI 응답이 비어 있습니다.");
        }
        return verify(extracted, referenceObjectIds(screenContext));
    }

    /** AI가 돌려준 값. 형식이 틀릴 수 있어 날짜도 문자열로 받는다. */
    public record Extracted(
            String purpose, List<String> targets, String from, String to, String vaguePeriod) {}

    static QuestionInterpretation verify(Extracted extracted, List<Long> referenceObjectIds) {
        LocalDate from = parseDate(extracted.from());
        LocalDate to = parseDate(extracted.to());
        if (from == null || to == null || from.isAfter(to)) {
            from = null;
            to = null;
        }
        List<String> targets =
                extracted.targets() == null
                        ? List.of()
                        : extracted.targets().stream()
                                .filter(Objects::nonNull)
                                .map(String::strip)
                                .filter(target -> !target.isEmpty())
                                .map(QuestionInterpreter::limit)
                                .distinct()
                                .limit(MAX_TARGETS)
                                .toList();
        String purpose = blankToNull(extracted.purpose());
        return new QuestionInterpretation(
                purpose,
                targets,
                from,
                to,
                referenceObjectIds,
                blankToNull(extracted.vaguePeriod()));
    }

    private String userPrompt(
            String question, Map<String, Object> screenContext, PendingClarification pending) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("오늘: ").append(LocalDate.now(clock.withZone(USER_ZONE))).append('\n');
        if (screenContext.get("period") instanceof Map<?, ?> period) {
            prompt.append("화면 기간: ")
                    .append(period.get("from"))
                    .append(" ~ ")
                    .append(period.get("to"))
                    .append('\n');
        }
        if (pending != null) {
            QuestionInterpretation previous = pending.previous();
            prompt.append("이전에 되물은 내용: ")
                    .append(pending.askedQuestion())
                    .append('\n')
                    .append("이미 확인한 조건: 목적=")
                    .append(previous.purpose())
                    .append(", 대상=")
                    .append(previous.targets())
                    .append(", 기간=")
                    .append(previous.hasPeriod() ? previous.from() + " ~ " + previous.to() : "없음")
                    .append('\n');
        }
        return prompt.append("질문: ").append(question).toString();
    }

    private static List<Long> referenceObjectIds(Map<String, Object> screenContext) {
        if (!(screenContext.get("referenceObjectIds") instanceof List<?> ids)) {
            return List.of();
        }
        return ids.stream()
                .filter(Number.class::isInstance)
                .map(id -> ((Number) id).longValue())
                .toList();
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return limit(value.strip());
    }

    private static String limit(String value) {
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }
}
