package com.axcore.platform.ontology.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.MaskedProperties;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.repository.ObjectQueryRepository;
import com.axcore.platform.ontology.repository.ObjectQueryRepository.ObjectRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
public class ObjectQueryService implements ObjectSearchQuery {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectQueryRepository objects;

    // 권한, 목록, 전체 건수를 같은 스냅샷에서 읽는다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request) {
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

        List<Long> maskedTypeIds =
                visibleTypeIds.stream().filter(id -> !access.canViewRestricted(id)).toList();
        String q = request.q();
        String pattern = q == null || q.isBlank() ? null : SearchPattern.contains(q);
        return objects.search(visibleTypeIds, maskedTypeIds, status, pattern, page)
                .map(row -> toResponse(row, access));
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
