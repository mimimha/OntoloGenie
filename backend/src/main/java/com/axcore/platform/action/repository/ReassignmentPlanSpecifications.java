package com.axcore.platform.action.repository;

import com.axcore.platform.action.domain.ReassignmentPlan;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** API 093 조회 조건. 전달된 조건만 AND로 묶고, null인 조건으로는 거르지 않는다. */
public final class ReassignmentPlanSpecifications {

    private ReassignmentPlanSpecifications() {}

    public static Specification<ReassignmentPlan> byFilters(Long fromEquipmentId, Long createdBy) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (fromEquipmentId != null) {
                conditions.add(criteriaBuilder.equal(root.get("fromEquipmentId"), fromEquipmentId));
            }
            if (createdBy != null) {
                conditions.add(criteriaBuilder.equal(root.get("createdBy"), createdBy));
            }
            return criteriaBuilder.and(conditions.toArray(new Predicate[0]));
        };
    }
}
