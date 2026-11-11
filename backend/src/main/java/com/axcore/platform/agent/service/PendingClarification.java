package com.axcore.platform.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 마지막 답변이 되묻기였다(143). 이번 질문은 그 답이므로 되물은 문장과 그때까지 알아낸 조건을 함께 넘긴다. unresolvedTargets는 말했지만 객체로 정하지 못한
 * 대상이고, preferred는 다음 답에서 먼저 고를 객체(그때 보여 준 후보와 화면 참고 객체)다(147).
 */
public record PendingClarification(
        String askedQuestion,
        QuestionInterpretation previous,
        List<String> unresolvedTargets,
        List<AgentReferenceObject> preferred) {

    public PendingClarification(String askedQuestion, QuestionInterpretation previous) {
        this(askedQuestion, previous, List.of(), List.of());
    }

    /** 되묻기 답변의 내용과 answerMeta에서 읽는다. */
    static PendingClarification fromAnswer(String content, Map<String, Object> answerMeta) {
        Map<?, ?> clarification = map(answerMeta.get("clarification"));
        List<String> unresolved = new ArrayList<>();
        List<AgentReferenceObject> preferred = new ArrayList<>();
        if (clarification.get("targets") instanceof List<?> issues) {
            for (Object issue : issues) {
                Map<?, ?> found = map(issue);
                if (found.get("target") instanceof String target) {
                    unresolved.add(target);
                }
                if (found.get("candidates") instanceof List<?> candidates) {
                    candidates.stream()
                            .map(AgentReferenceObject::fromMap)
                            .filter(Objects::nonNull)
                            .forEach(preferred::add);
                }
            }
        }
        if (clarification.get("contextObjectIds") instanceof List<?> ids) {
            ids.stream()
                    .filter(Number.class::isInstance)
                    .map(id -> AgentReferenceObject.idOnly(((Number) id).longValue()))
                    .forEach(preferred::add);
        }
        return new PendingClarification(
                content,
                QuestionInterpretation.fromAnswerMeta(answerMeta),
                List.copyOf(unresolved),
                List.copyOf(preferred));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> found ? found : Map.of();
    }
}
