package com.axcore.platform.audit.service;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse;
import com.axcore.platform.audit.domain.ActionExecution;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.AuditExecutionSearchRequest;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
import com.axcore.platform.audit.repository.ActionExecutionRepository;
import com.axcore.platform.audit.repository.AuditExecutionQueryRepository;
import com.axcore.platform.audit.repository.AuditExecutionQueryRepository.Filter;
import com.axcore.platform.audit.repository.EntityChangeRepository;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행·감사 이력 조회 (API 096·097·098·080, FN-LOG-01~04·FN-EXP-03). Agent 조회 기록(AGENT_QUERY)은 어디에도 보여 주지
 * 않는다. 080은 역할의 객체 조회 범위(S15P31C106-205)를 따른다. 080·096은 ADMIN이 아니면 사용자가 볼 수 없는 민감 속성 값을 가린다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditExecutionQueryService {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_MINE = "MINE";
    private static final String ADMIN = "ADMIN";
    private static final String OBJECT_INSTANCE = "object_instance";

    private final AuditExecutionQueryRepository history;
    private final ActionExecutionRepository executions;
    private final EntityChangeRepository changes;
    private final DataAccessPolicy dataAccessPolicy;
    private final AuditValueMasker masker;

    /** 096. ADMIN은 전체, 그 밖의 역할은 본인 실행(본인이 요청한 작업의 결과 줄 포함)만 본다. */
    public Page<AuditExecutionResponse> search(
            LoginAccount viewer, AuditExecutionSearchRequest request, Pageable pageable) {
        if (request.eventKind() == EventKind.AGENT_QUERY) {
            throw BusinessException.invalidField("eventKind", "Agent 조회 기록은 조회할 수 없습니다.");
        }
        if (request.targetId() != null && request.targetType() == null) {
            throw BusinessException.invalidField("targetId", "targetId는 targetType과 함께 보내야 합니다.");
        }
        boolean admin = isAdmin(viewer);
        if (!admin && request.actorId() != null && !request.actorId().equals(viewer.id())) {
            throw forbidden();
        }
        Filter filter =
                new Filter(
                        request.actorId(),
                        request.actionCode(),
                        request.status(),
                        request.entryPoint(),
                        request.eventKind(),
                        request.category(),
                        request.targetType(),
                        request.targetId(),
                        request.from(),
                        request.to());
        Page<AuditExecutionResponse> page =
                history.search(filter, admin ? null : viewer.id(), pageable);
        return admin ? page : masked(page, dataAccessPolicy.of(viewer));
    }

    public static String scopeOf(LoginAccount viewer) {
        return isAdmin(viewer) ? SCOPE_ALL : SCOPE_MINE;
    }

    /** 097·098. ADMIN만 본다. 없는 기록과 Agent 조회 기록은 404다. */
    public ActionExecutionDetailResponse get(LoginAccount viewer, long id) {
        if (!isAdmin(viewer)) {
            throw forbidden();
        }
        ActionExecution execution =
                executions
                        .findById(id)
                        .filter(found -> found.getEventKind() != EventKind.AGENT_QUERY)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "action_execution")));
        return ActionExecutionDetailResponse.of(
                execution, changes.findByActionExecutionIdOrderByIdAsc(id));
    }

    /**
     * 080. 객체를 대상으로 한 실행. ADMIN은 전체, 그 밖의 역할은 실행자가 본인인 기록(본인을 대신한 Agent 포함)만 본다. 조회 범위 밖 유형의 객체는 없는
     * 객체와 같은 404로 막아 있다는 사실도 알리지 않는다(077과 같은 규칙).
     */
    public Page<AuditExecutionResponse> objectExecutions(
            LoginAccount viewer,
            long objectId,
            ObjectExecutionSearchRequest request,
            Pageable pageable) {
        DataAccess access = dataAccessPolicy.of(viewer);
        history.findObjectTypeId(objectId)
                .filter(access::canView)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", OBJECT_INSTANCE)));
        Filter filter =
                new Filter(
                        isAdmin(viewer) ? null : viewer.id(),
                        null,
                        request.status(),
                        null,
                        null,
                        null,
                        OBJECT_INSTANCE,
                        objectId,
                        request.from(),
                        request.to());
        Page<AuditExecutionResponse> page = history.search(filter, null, pageable);
        return isAdmin(viewer) ? page : masked(page, access);
    }

    private Page<AuditExecutionResponse> masked(
            Page<AuditExecutionResponse> page, DataAccess access) {
        return new PageImpl<>(
                masker.mask(page.getContent(), access),
                page.getPageable(),
                page.getTotalElements());
    }

    private static boolean isAdmin(LoginAccount viewer) {
        return Objects.equals(ADMIN, viewer.roleCode());
    }

    private static BusinessException forbidden() {
        return new BusinessException(
                CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", ADMIN));
    }
}
