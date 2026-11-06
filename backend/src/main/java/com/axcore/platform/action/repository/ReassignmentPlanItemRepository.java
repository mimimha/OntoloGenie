package com.axcore.platform.action.repository;

import com.axcore.platform.action.domain.ReassignmentPlanItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/** 재배정 계획 항목 조회 (API 093~095). 저장은 {@link ReassignmentPlanRepository}가 한다. */
public interface ReassignmentPlanItemRepository extends Repository<ReassignmentPlanItem, Long> {

    /** 여러 계획의 항목을 한 번에 읽는다. 계획마다 따로 읽지 않는다(N+1 방지). 저장한 순서(id)대로다. */
    List<ReassignmentPlanItem> findByPlanIdInOrderById(Collection<Long> planIds);
}
