package com.axcore.platform.agent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문에서 뽑은 조회 조건(142). AI 출력을 서버에서 검사한 뒤의 값이다. 답변의 answerMeta.conditions로 저장해 다음 질문 때 읽는다(대화방 상태,
 * 2026-11-04 결정).
 */
public record QuestionInterpretation(
        String purpose,
        List<String> targets,
        LocalDate from,
        LocalDate to,
        List<Long> referenceObjectIds,
        String vaguePeriod) {

    public boolean hasPeriod() {
        return from != null && to != null;
    }

    /** 되물어야 하는 조건(143). 기간은 모호한 표현만 있을 때 묻고, 기간을 말하지 않은 질문은 묻지 않는다. */
    public List<MissingCondition> missing() {
        List<MissingCondition> missing = new ArrayList<>();
        if (targets.isEmpty() && referenceObjectIds.isEmpty()) {
            missing.add(MissingCondition.TARGET);
        }
        if (purpose == null) {
            missing.add(MissingCondition.PURPOSE);
        }
        if (vaguePeriod != null && !hasPeriod()) {
            missing.add(MissingCondition.PERIOD);
        }
        return missing;
    }

    /** 되물은 뒤 받은 답과 합친다(143). 이번 답에서 새로 확인한 값만 이전 조건을 덮는다. */
    public QuestionInterpretation fillFrom(QuestionInterpretation previous) {
        return new QuestionInterpretation(
                purpose != null ? purpose : previous.purpose,
                !targets.isEmpty() ? targets : previous.targets,
                hasPeriod() ? from : previous.from,
                hasPeriod() ? to : previous.to,
                !referenceObjectIds.isEmpty() ? referenceObjectIds : previous.referenceObjectIds,
                vaguePeriod != null ? vaguePeriod : previous.vaguePeriod);
    }

    /** answerMeta.conditions 값. 확인하지 못한 조건은 null로 둔다. */
    public Map<String, Object> toConditions() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("purpose", purpose);
        conditions.put("targets", targets);
        conditions.put(
                "period",
                hasPeriod() ? Map.of("from", from.toString(), "to", to.toString()) : null);
        conditions.put("referenceObjectIds", referenceObjectIds);
        return conditions;
    }

    /** answerMeta.clarification 값. 모호한 기간 표현은 다음 답과 합칠 때 다시 묻기 위해 남긴다. */
    public Map<String, Object> toClarification() {
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("missing", missing().stream().map(Enum::name).toList());
        clarification.put("vaguePeriod", vaguePeriod);
        return clarification;
    }

    /** 되묻기 답변의 answerMeta에서 그때까지 알아낸 조건을 다시 읽는다(143). */
    public static QuestionInterpretation fromAnswerMeta(Map<String, Object> answerMeta) {
        Map<?, ?> conditions = map(answerMeta.get("conditions"));
        Map<?, ?> period = map(conditions.get("period"));
        Map<?, ?> clarification = map(answerMeta.get("clarification"));
        return new QuestionInterpretation(
                text(conditions.get("purpose")),
                strings(conditions.get("targets")),
                date(period.get("from")),
                date(period.get("to")),
                ids(conditions.get("referenceObjectIds")),
                text(clarification.get("vaguePeriod")));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> found ? found : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String found ? found : null;
    }

    private static LocalDate date(Object value) {
        return value instanceof String found ? LocalDate.parse(found) : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static List<Long> ids(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Number.class::isInstance)
                .map(id -> ((Number) id).longValue())
                .toList();
    }
}
