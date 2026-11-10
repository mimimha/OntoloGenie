package com.axcore.platform.ontology.domain;

import java.util.Set;

/** 같은 비교값을 가진 출발·도착 식별자로, 자기 연결을 제외한 관계 수를 판단한다. */
public record LinkMatchGroup<T>(Set<T> sources, Set<T> targets, boolean excludesSelf) {

    public boolean includes(T source, T target) {
        return !excludesSelf || !source.equals(target);
    }

    public int targetCount(T source) {
        return targets.size() - (excludesSelf && targets.contains(source) ? 1 : 0);
    }

    public boolean hasCompetingSources(T source) {
        int targetCount = targetCount(source);
        if (targetCount == 0 || sources.size() < 2) {
            return false;
        }
        if (!excludesSelf || sources.size() > 2) {
            return true;
        }
        // 출발이 둘이면, 다른 출발 자기 자신만 도착 후보일 때에만 경쟁이 없다.
        return targetCount > 1
                || sources.stream()
                        .noneMatch(other -> !source.equals(other) && targets.contains(other));
    }
}
