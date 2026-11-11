package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문에 나온 대상 이름·코드를 실제 객체로 찾는다(147). AI에게 맡기지 않고 서버가 질문한 사용자의 권한으로 076 객체 검색을 불러, 볼 수 없는 객체는 찾지 않는다.
 * 하나로 정해지지 않으면 후보를 돌려주어 사용자에게 되묻게 한다. 검색은 조회 기록에 남기지만 정보별 결과에는 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AgentTargetResolver {
    static final String OBJECT_SEARCH = "OBJECT_SEARCH";
    static final String LABEL = "대상 찾기";

    /** 되물을 때 보여 줄 후보 수. */
    static final int MAX_CANDIDATES = 5;

    /**
     * 이름이 똑같은 객체를 고르려고 076이 허용하는 최대 건수를 읽는다. 076은 "포함" 검색을 ID 순으로 돌려주므로, 비슷한 이름이 이보다 많으면 똑같은 객체가 빠질
     * 수 있다. 정확히 같은 이름을 먼저 돌려주는 정렬은 076 담당과 협의한다.
     */
    private static final int SEARCH_SIZE = 100;

    /** "06번"처럼 번호에 붙인 말. 그대로 찾아 없으면 떼고 다시 찾는다. */
    private static final String NUMBER_SUFFIX = "번";

    /** 같은 이름 후보를 되물을 때 붙인 구별 번호로 고른 말. 예: 번호 306 */
    private static final Pattern CHOSEN_NUMBER = Pattern.compile("번호\\s*(\\d{1,18})");

    private final ObjectSearchQuery objects;

    /** 하나로 정하지 못한 대상. 후보가 비었으면 찾지 못한 것이다. */
    public record TargetIssue(String target, List<AgentReferenceObject> candidates) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", target);
            map.put("candidates", candidates.stream().map(AgentReferenceObject::toMap).toList());
            return map;
        }
    }

    public record Resolution(List<AgentReferenceObject> found, List<TargetIssue> issues) {}

    /**
     * known은 대화에서 이미 다룬 객체(화면 참고 객체, 이전 답변의 대상, 되물을 때 보여 준 후보)다. 그중 이름이 같은 것이 하나뿐이면 다시 찾지 않고, 되물을 때
     * 보여 준 "번호 N"으로 고르면 그것으로 정한다. 후보가 여럿이어도 그중 하나만 known이면 그것으로 정한다.
     */
    public Resolution resolve(
            List<String> targets, List<AgentReferenceObject> known, AgentQuerySession session) {
        Set<Long> knownIds =
                known.stream().map(AgentReferenceObject::id).collect(Collectors.toSet());
        Map<Long, AgentReferenceObject> found = new LinkedHashMap<>();
        List<TargetIssue> issues = new ArrayList<>();
        for (String target : targets) {
            Optional<AgentReferenceObject> chosen =
                    chosenByNumber(target, known).or(() -> onlyKnownNamed(target, known));
            if (chosen.isPresent()) {
                found.putIfAbsent(chosen.get().id(), chosen.get());
                continue;
            }
            String query = target;
            List<ObjectSearchResponse> candidates = search(query, session);
            if (candidates.isEmpty() && target.endsWith(NUMBER_SUFFIX)) {
                String shorter =
                        target.substring(0, target.length() - NUMBER_SUFFIX.length()).strip();
                if (!shorter.isEmpty()) {
                    query = shorter;
                    candidates = search(query, session);
                }
            }
            Set<String> names = new HashSet<>(List.of(target, query));
            Optional<ObjectSearchResponse> picked = pick(candidates, names, knownIds);
            if (picked.isPresent()) {
                AgentReferenceObject object = AgentReferenceObject.from(picked.get());
                found.putIfAbsent(object.id(), object);
            } else {
                // 이름이 똑같은 객체가 여럿이면 그것들을 후보로 보여 준다.
                List<ObjectSearchResponse> exact = sameNamed(candidates, names);
                issues.add(
                        new TargetIssue(
                                target,
                                (exact.isEmpty() ? candidates : exact)
                                        .stream()
                                                .limit(MAX_CANDIDATES)
                                                .map(AgentReferenceObject::from)
                                                .toList()));
            }
        }
        return new Resolution(List.copyOf(found.values()), issues);
    }

    /** 이름이나 속성 값이 똑같은 것이 하나면 그것, 아니면 이미 대상이던 것이 하나면 그것, 후보가 하나뿐이면 그것을 고른다. */
    static Optional<ObjectSearchResponse> pick(
            List<ObjectSearchResponse> candidates, Set<String> names, Set<Long> knownIds) {
        List<ObjectSearchResponse> exact = sameNamed(candidates, names);
        if (exact.size() == 1) {
            return Optional.of(exact.getFirst());
        }
        List<ObjectSearchResponse> pool = exact.isEmpty() ? candidates : exact;
        List<ObjectSearchResponse> known =
                pool.stream().filter(candidate -> knownIds.contains(candidate.id())).toList();
        if (known.size() == 1) {
            return Optional.of(known.getFirst());
        }
        return pool.size() == 1 ? Optional.of(pool.getFirst()) : Optional.empty();
    }

    /** 이름이 같은 객체를 대화에서 이미 하나만 다뤘으면 그것. 같은 이름이 여럿이면 고르지 않는다. */
    private static Optional<AgentReferenceObject> onlyKnownNamed(
            String target, List<AgentReferenceObject> known) {
        List<AgentReferenceObject> named =
                known.stream().filter(object -> target.equalsIgnoreCase(object.name())).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** 되물을 때 같은 이름 후보를 구별하려고 보여 준 "번호 N"으로 골랐으면 그 후보. 이름을 아는 객체(보여 준 후보)만 고른다. */
    private static Optional<AgentReferenceObject> chosenByNumber(
            String target, List<AgentReferenceObject> known) {
        Matcher number = CHOSEN_NUMBER.matcher(target.strip());
        if (!number.matches()) {
            return Optional.empty();
        }
        long id = Long.parseLong(number.group(1));
        return known.stream()
                .filter(object -> object.id() == id && object.name() != null)
                .findFirst();
    }

    private static List<ObjectSearchResponse> sameNamed(
            List<ObjectSearchResponse> candidates, Set<String> names) {
        return candidates.stream().filter(candidate -> sameName(candidate, names)).toList();
    }

    /**
     * 076은 표시 이름과 식별 속성으로 검색하지만 응답에는 어느 속성이 식별 속성인지 없다. 그래서 보이는 속성 값 전체와 비교한다. 숨긴 속성은 응답에서 이미 가려져
     * 있다.
     */
    private static boolean sameName(ObjectSearchResponse candidate, Set<String> names) {
        if (names.stream().anyMatch(name -> name.equalsIgnoreCase(candidate.displayName()))) {
            return true;
        }
        if (candidate.properties() == null) {
            return false;
        }
        return candidate.properties().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(value -> names.stream().anyMatch(value::equalsIgnoreCase));
    }

    private List<ObjectSearchResponse> search(String query, AgentQuerySession session) {
        List<ObjectSearchResponse> rows = new ArrayList<>();
        session.lookup(
                OBJECT_SEARCH,
                LABEL,
                Map.of("q", query),
                () -> {
                    rows.addAll(
                            objects.search(
                                            session.asker(),
                                            new ObjectSearchRequest(
                                                    query, null, null, 0, SEARCH_SIZE))
                                    .getContent());
                    return new AgentQuerySession.Outcome(
                            null,
                            rows.isEmpty()
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            rows.stream()
                                    .limit(MAX_CANDIDATES)
                                    .map(row -> Map.<String, Object>of("objectId", row.id()))
                                    .toList(),
                            null);
                });
        return rows;
    }
}
