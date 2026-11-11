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
              "현재", "지금"처럼 지금 상태를 물으면 from, to를 모두 오늘로 쓴다.
            - vaguePeriod: "최근", "요즘", "지난번"처럼 기간을 말했지만 범위가 정해지지 않은 표현이면 그 표현을 그대로 쓰고
              from, to는 null로 둔다. 기간을 임의로 정하지 않는다. 그런 표현이 없으면 null.
            - targets에는 특정한 대상 하나하나의 이름이나 코드만 쓴다.
            - kinds: "전체 설비", "설비별", "진행 중인 작업지시"처럼 특정 대상이 아니라 종류 전체를 물으면 그 종류 이름을 쓴다(예: 설비).
              이때 targets에는 넣지 않는다. 그런 질문이 아니면 빈 배열.
            "이전에 되물은 내용"이 있으면 질문은 그에 대한 사용자의 답이다. 답에서 새로 알려 준 값만 뽑고, 이미 확인한 조건은 다시 쓰지 않는다.
            - 다만 되물은 내용에 답하지 않고 다른 것을 새로 물으면 새 질문으로 보고 목적과 대상·종류를 모두 뽑는다.
            - 되물은 후보에 "번호 N"이 있고 사용자가 그 번호로 고르면 targets에 "번호 N"을 그대로 쓴다.
            "이전 답변의 조건"이 있으면 질문은 같은 대화의 후속 질문이다. 질문에서 새로 말한 값만 뽑는다.
            - 대상을 다시 말하지 않았거나 "그거", "같은 설비"처럼 이전 대상을 가리키면 targets와 kinds는 빈 배열로 둔다.
            - "그럼 지난달은?"처럼 기간만 바꾸면 기간만 쓰고 purpose는 null로 둔다.
            """;

    private final ObjectProvider<ChatModel> chatModels;
    private final Clock clock;

    /** GMS 설정이 없어 AI 모델이 만들어지지 않았으면 false. */
    public boolean isConfigured() {
        return chatModels.getIfAvailable() != null;
    }

    /**
     * AI 호출이 실패하거나 응답이 비면 예외를 던진다. 부르는 쪽이 답변을 실패로 끝낸다. pending은 직전 되묻기, previous는 직전 정상 답변의 조건이며
     * 없으면 null이다(147). pending이 있으면 되묻기에 대한 답으로 본다.
     */
    public QuestionInterpretation interpret(
            String question,
            Map<String, Object> screenContext,
            PendingClarification pending,
            QuestionInterpretation previous) {
        Extracted extracted =
                ChatClient.create(chatModels.getObject())
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt(question, screenContext, pending, previous))
                        .call()
                        .entity(Extracted.class);
        if (extracted == null) {
            throw new IllegalStateException("AI 응답이 비어 있습니다.");
        }
        return verify(extracted, referenceObjects(screenContext));
    }

    /** AI가 돌려준 값. 형식이 틀릴 수 있어 날짜도 문자열로 받는다. */
    public record Extracted(
            String purpose,
            List<String> targets,
            String from,
            String to,
            String vaguePeriod,
            List<String> kinds) {}

    static QuestionInterpretation verify(
            Extracted extracted, List<AgentReferenceObject> referenceObjects) {
        LocalDate from = parseDate(extracted.from());
        LocalDate to = parseDate(extracted.to());
        if (from == null || to == null || from.isAfter(to)) {
            from = null;
            to = null;
        }
        String purpose = blankToNull(extracted.purpose());
        return new QuestionInterpretation(
                purpose,
                names(extracted.targets()),
                from,
                to,
                referenceObjects,
                blankToNull(extracted.vaguePeriod()),
                names(extracted.kinds()));
    }

    private static List<String> names(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(QuestionInterpreter::limit)
                .distinct()
                .limit(MAX_TARGETS)
                .toList();
    }

    private String userPrompt(
            String question,
            Map<String, Object> screenContext,
            PendingClarification pending,
            QuestionInterpretation previous) {
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
            prompt.append("이전에 되물은 내용: ")
                    .append(pending.askedQuestion())
                    .append('\n')
                    .append("이미 확인한 조건: ")
                    .append(describe(pending.previous()))
                    .append('\n');
        } else if (previous != null) {
            prompt.append("이전 답변의 조건: ").append(describe(previous)).append('\n');
        }
        return prompt.append("질문: ").append(question).toString();
    }

    /** AI에게 알려 줄 조건 설명. 대상 객체는 이름까지 알려 "그거"가 무엇인지 알 수 있게 한다(147). */
    static String describe(QuestionInterpretation conditions) {
        return "목적="
                + conditions.purpose()
                + ", 대상="
                + conditions.targets()
                + ", 대상 객체="
                + conditions.referenceObjects().stream()
                        .map(AgentReferenceObject::describe)
                        .toList()
                + ", 종류 전체="
                + conditions.kinds()
                + ", 기간="
                + (conditions.hasPeriod() ? conditions.from() + " ~ " + conditions.to() : "없음");
    }

    /** 화면이 보낸 참고 객체. 화면은 이름을 보내지 않으므로 ID만 둔다. */
    private static List<AgentReferenceObject> referenceObjects(Map<String, Object> screenContext) {
        if (!(screenContext.get("referenceObjectIds") instanceof List<?> ids)) {
            return List.of();
        }
        return ids.stream()
                .filter(Number.class::isInstance)
                .map(id -> AgentReferenceObject.idOnly(((Number) id).longValue()))
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
