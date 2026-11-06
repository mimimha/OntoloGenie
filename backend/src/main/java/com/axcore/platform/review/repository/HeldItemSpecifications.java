package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.HeldItem;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class HeldItemSpecifications {

    private HeldItemSpecifications() {}

    public static Specification<HeldItem> byFilters(String status, Long linkTypeId) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> conditions = new ArrayList<>();

            if (status != null) {
                conditions.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (linkTypeId != null) {
                conditions.add(criteriaBuilder.equal(root.get("linkTypeId"), linkTypeId));
            }

            return criteriaBuilder.and(conditions.toArray(Predicate[]::new));
        };
    }
}
