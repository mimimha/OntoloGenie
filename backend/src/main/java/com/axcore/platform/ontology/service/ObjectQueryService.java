package com.axcore.platform.ontology.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.MaskedProperties;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.dto.ObjectLinkGroupResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.repository.ObjectQueryRepository;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.DetailRow;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.ObjectRow;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.PropertyFilter;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.SearchFilters;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ObjectQueryService
        implements ObjectSearchQuery, ObjectDetailQuery, ObjectEvidenceQuery {

    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 공백만 다른 저장 값은 하나인지만 알면 된다. 같은 값이 섞여 있어도 판단할 수 있게 넉넉히 읽는다. */
    private static final int MAX_SPACING_VARIANTS = 10;

    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectQueryRepository objects;
    private final ObjectSearchFilterValidator filterValidator;

    // 권한, 목록, 전체 건수를 같은 스냅샷에서 읽는다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request) {
        if (request.hasFilters() && request.objectTypeId() == null) {
            throw BusinessException.invalidField("objectTypeId", "속성 또는 기간 조건에는 객체 유형을 지정해 주세요.");
        }
        Status status = request.status() == null ? Status.ACTIVE : request.status();
        if (status == Status.DELETED_AT_SOURCE && !RoleCode.ADMIN.name().equals(actor.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN,
                    "원천에서 삭제된 객체는 관리자만 조회할 수 있습니다.",
                    Map.of("field", "status", "requiredPermission", RoleCode.ADMIN.name()));
        }

        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        DataAccess access = dataAccessPolicy.of(actor);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (request.objectTypeId() != null) {
            visibleTypeIds =
                    visibleTypeIds.contains(request.objectTypeId())
                            ? Set.of(request.objectTypeId())
                            : Set.of();
        }
        if (visibleTypeIds.isEmpty()) {
            return Page.empty(page);
        }

        String q = request.q();
        String exactPattern = q == null || q.isBlank() ? null : SearchPattern.exact(q);
        SearchFilters filters = filterValidator.validate(request, access);
        if (!filters.values().isEmpty()) {
            filters = matchStoredSpacing(filters, request.objectTypeId(), status);
        }
        return objects.search(
                        visibleTypeIds,
                        maskedTypes(access, visibleTypeIds),
                        status,
                        exactPattern,
                        filters,
                        page)
                .map(row -> toResponse(row, access));
    }

    /**
     * 문자 조건 값과 똑같은 저장 값이 없고 공백만 다른 저장 값이 하나뿐이면 그 값으로 거른다(378). "정비 중"으로 물으면 저장 값 "정비중"을 찾는다. 같은 값이
     * 있으면 그대로 두고, 공백만 다른 값이 여럿이면 어느 것인지 정할 수 없어 그대로 둔다(0건).
     */
    private SearchFilters matchStoredSpacing(SearchFilters filters, long typeId, Status status) {
        List<PropertyFilter> matched = new ArrayList<>();
        for (PropertyFilter filter : filters.values()) {
            if (filter.type() != PropertyDataType.STRING) {
                matched.add(filter);
                continue;
            }
            List<String> stored =
                    objects.findSpacingVariants(
                            typeId, filter.code(), filter.value(), status, MAX_SPACING_VARIANTS);
            boolean replace = stored.size() == 1 && !stored.contains(filter.value());
            matched.add(
                    replace
                            ? new PropertyFilter(filter.code(), filter.type(), stored.getFirst())
                            : filter);
        }
        return new SearchFilters(List.copyOf(matched), filters.period());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectSearchResponse> findByPropertyValue(
            LoginAccount actor, String value, int limit) {
        String exact = value == null ? "" : value.strip();
        if (exact.isEmpty()) {
            return List.of();
        }
        DataAccess access = dataAccessPolicy.of(actor);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty()) {
            return List.of();
        }
        return objects
                .findByPropertyValue(
                        visibleTypeIds, maskedTypes(access, visibleTypeIds), exact, limit)
                .stream()
                .map(row -> toResponse(row, access))
                .toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectDetailResponse detail(LoginAccount actor, long objectId) {
        DataAccess access = dataAccessPolicy.of(actor);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty()) {
            throw notFound(objectId);
        }
        DetailRow detail =
                objects.findDetail(
                                objectId,
                                visibleTypeIds,
                                maskedTypes(access, visibleTypeIds),
                                RoleCode.ADMIN.name().equals(actor.roleCode()))
                        .orElseThrow(() -> notFound(objectId));
        ObjectRow row = detail.object();
        MaskedProperties masked = access.mask(row.objectTypeId(), row.properties());
        return new ObjectDetailResponse(
                row.id(),
                row.objectTypeId(),
                row.objectTypeName(),
                row.displayName(),
                masked.properties(),
                detail.propertyNames(),
                masked.restrictedPropertyCodes(),
                row.status(),
                row.sourceMappingId(),
                detail.sourceSystemName(),
                detail.sourceResourceKey(),
                row.sourceKey(),
                row.firstCollectedAt(),
                row.lastSyncedAt(),
                dataAsOf(row.asOfCode(), masked.properties()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectLinkResponse> links(
            LoginAccount actor, long objectId, ObjectLinkSearchRequest request) {
        DataAccess access = dataAccessPolicy.of(actor);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty() || !objects.existsActive(objectId, visibleTypeIds)) {
            throw notFound(objectId);
        }
        Direction direction = request.direction() == null ? Direction.BOTH : request.direction();
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return objects.links(
                objectId,
                visibleTypeIds,
                maskedTypes(access, visibleTypeIds),
                request.linkTypeId(),
                direction,
                page);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ObjectLinkGroupResponse> linkGroups(LoginAccount actor, long objectId) {
        Set<Long> visibleTypeIds = dataAccessPolicy.of(actor).visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty() || !objects.existsActive(objectId, visibleTypeIds)) {
            throw notFound(objectId);
        }
        return objects.linkGroups(objectId, visibleTypeIds);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectLinkEvidenceResponse linkEvidence(LoginAccount actor, long objectId, long linkId) {
        DataAccess access = dataAccessPolicy.of(actor);
        Set<Long> visibleTypeIds = access.visibleObjectTypeIds();
        if (!visibleTypeIds.isEmpty()) {
            var evidence =
                    objects.findLinkEvidence(
                            objectId, linkId, visibleTypeIds, maskedTypes(access, visibleTypeIds));
            if (evidence.isPresent()) {
                return evidence.get();
            }
        }
        throw new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "object_link", "id", linkId));
    }

    private static List<Long> maskedTypes(DataAccess access, Set<Long> visibleTypeIds) {
        return visibleTypeIds.stream().filter(id -> !access.canViewRestricted(id)).toList();
    }

    private static BusinessException notFound(long objectId) {
        return new BusinessException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                Map.of("resource", "object_instance", "id", objectId));
    }

    private static ObjectSearchResponse toResponse(ObjectRow row, DataAccess access) {
        MaskedProperties masked = access.mask(row.objectTypeId(), row.properties());
        return new ObjectSearchResponse(
                row.id(),
                row.objectTypeId(),
                row.objectTypeName(),
                row.displayName(),
                masked.properties(),
                masked.restrictedPropertyCodes(),
                row.status(),
                row.sourceMappingId(),
                row.sourceKey(),
                row.firstCollectedAt(),
                row.lastSyncedAt(),
                dataAsOf(row.asOfCode(), masked.properties()));
    }

    private static Instant dataAsOf(String code, Map<String, Object> properties) {
        if (code == null || !(properties.get(code) instanceof String value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
