package com.axcore.platform.action.repository;

import com.axcore.platform.action.domain.ReassignmentPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * 저장된 재배정 계획 조회 (API 093·094). 저장은 {@link ReassignmentPlanRepository}가 하므로 저장 메서드를 열지 않는다. 목록 조건은
 * {@link ReassignmentPlanSpecifications}로 건다.
 */
public interface ReassignmentPlanQueryRepository
        extends Repository<ReassignmentPlan, Long>, JpaSpecificationExecutor<ReassignmentPlan> {

    Optional<ReassignmentPlan> findById(long id);
}
