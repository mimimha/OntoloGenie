package com.axcore.platform.action.service;

import com.axcore.platform.action.domain.ReassignmentPlan;
import com.axcore.platform.action.dto.ReassignmentPlanItemResponse;
import com.axcore.platform.action.dto.ReassignmentPlanResponse;
import com.axcore.platform.action.repository.ReassignmentPlanItemRepository;
import com.axcore.platform.action.repository.ReassignmentPlanQueryRepository;
import com.axcore.platform.action.repository.ReassignmentPlanSpecifications;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 재배정 계획 조회 (API 093~095, FN-ACT-06). 로그인한 모든 역할이 볼 수 있다(D4). 계획 생성은 091로만 하고, 조회는 원천 MES를 바꾸지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReassignmentPlanQueryService {

    /** 최근에 만든 계획부터. 요청의 정렬은 받지 않는다. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ReassignmentPlanQueryRepository plans;
    private final ReassignmentPlanItemRepository planItems;

    /** 조건이 null이면 거르지 않는다. 계획 한 페이지를 고른 뒤 그 계획들의 항목은 한 번에 붙인다. */
    public Page<ReassignmentPlanResponse> search(
            Long fromEquipmentId, Long createdBy, Pageable pageable) {
        Page<ReassignmentPlan> page =
                plans.findAll(
                        ReassignmentPlanSpecifications.byFilters(fromEquipmentId, createdBy),
                        PageRequest.of(
                                pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
        return new PageImpl<>(withItems(page.getContent()), pageable, page.getTotalElements());
    }

    public ReassignmentPlanResponse get(long id) {
        ReassignmentPlan plan =
                plans.findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "reassignment_plan")));
        return withItems(List.of(plan)).getFirst();
    }

    public List<ReassignmentPlanItemResponse> items(long id) {
        return get(id).items();
    }

    private List<ReassignmentPlanResponse> withItems(List<ReassignmentPlan> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ReassignmentPlanItemResponse>> itemsByPlan = new HashMap<>();
        planItems
                .findByPlanIdInOrderById(found.stream().map(ReassignmentPlan::getId).toList())
                .forEach(
                        item ->
                                itemsByPlan
                                        .computeIfAbsent(
                                                item.getPlanId(), planId -> new ArrayList<>())
                                        .add(ReassignmentPlanItemResponse.from(item)));
        return found.stream()
                .map(
                        plan ->
                                ReassignmentPlanResponse.from(
                                        plan, itemsByPlan.getOrDefault(plan.getId(), List.of())))
                .toList();
    }
}
