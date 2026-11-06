package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.IssueStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** API 065 조회 조건. 전달된 조건만 AND로 묶는다. from·to는 등록 시각 기준이고 둘 다 포함한다. */
public final class IssueSpecifications {

    private IssueSpecifications() {}

    public static Specification<Issue> byFilters(
            IssueStatus status, IssueKind issueKind, Long linkMappingId, Instant from, Instant to) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> conditions = new ArrayList<>();

            if (status != null) {
                conditions.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (issueKind != null) {
                conditions.add(criteriaBuilder.equal(root.get("issueKind"), issueKind));
            }

            if (linkMappingId != null) {
                conditions.add(criteriaBuilder.equal(root.get("linkMappingId"), linkMappingId));
            }

            if (from != null) {
                conditions.add(
                        criteriaBuilder.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            }

            if (to != null) {
                conditions.add(
                        criteriaBuilder.lessThanOrEqualTo(root.<Instant>get("createdAt"), to));
            }

            return criteriaBuilder.and(conditions.toArray(Predicate[]::new));
        };
    }
}
