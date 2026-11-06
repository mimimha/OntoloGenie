package com.axcore.platform.auth.service;

import com.axcore.platform.auth.repository.DataScopeRepository.ObjectTypeScope;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 한 사용자가 볼 수 있는 객체 범위(S15P31C106-205). {@link DataAccessPolicy#of}로 요청마다 만들어 그 요청 안에서만 쓴다. 객체 조회
 * API(076·077·078)와 Agent 조회 Tool이 데이터를 돌려주기 전에 같은 기준으로 확인한다.
 *
 * <ul>
 *   <li>ADMIN은 모든 유형을 보고 민감 값도 본다.
 *   <li>그 밖의 역할은 유형의 접근 그룹(object_type.access_group)이 역할의 허용 그룹(role_data_scope)에 있어야 본다. 그룹이 없는
 *       유형과 허용 행이 없는 그룹은 보이지 않는다.
 *   <li>민감(RESTRICTED) 값은 그 허용 행의 can_view_restricted가 true일 때만 본다.
 * </ul>
 */
public final class DataAccess {

    private final Map<Long, ObjectTypeScope> scopes;

    DataAccess(List<ObjectTypeScope> scopes) {
        this.scopes =
                scopes.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ObjectTypeScope::objectTypeId, Function.identity()));
    }

    /** 볼 수 있는 객체 유형 ID. 목록 검색은 이 값으로 SQL에서 거른다(건수에도 넣지 않는다). 비어 있으면 아무것도 보이지 않는다. */
    public Set<Long> visibleObjectTypeIds() {
        return scopes.values().stream()
                .filter(ObjectTypeScope::visible)
                .map(ObjectTypeScope::objectTypeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 없는 유형 ID면 false다. */
    public boolean canView(long objectTypeId) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        return scope != null && scope.visible();
    }

    /** 볼 수 없는 유형이면 false다. */
    public boolean canViewRestricted(long objectTypeId) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        return scope != null && scope.visible() && scope.canViewRestricted();
    }

    /** 볼 수 없는 유형의 객체는 없는 객체처럼 404로 거절한다. 객체가 있다는 사실도 알리지 않는다. */
    public void requireVisible(long objectTypeId, String resource, Object id) {
        if (!canView(objectTypeId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", resource, "id", id));
        }
    }

    /**
     * 민감 값을 볼 수 없으면 RESTRICTED 속성의 값을 null로 바꾸고 그 코드를 목록에 담는다. 키는 남긴다. properties에 키가 없는 속성은 가릴 값이
     * 없으니 목록에도 넣지 않는다. 원래 맵은 바꾸지 않는다. 범위를 읽을 때 없던 유형이면 가릴 속성을 모르므로 예외를 던진다.
     */
    public MaskedProperties mask(long objectTypeId, Map<String, Object> properties) {
        ObjectTypeScope scope = scopes.get(objectTypeId);
        if (scope == null) {
            throw new IllegalArgumentException("조회 범위에 없는 객체 유형입니다: " + objectTypeId);
        }
        Map<String, Object> copy = new LinkedHashMap<>(properties);
        if (canViewRestricted(objectTypeId)) {
            return new MaskedProperties(Collections.unmodifiableMap(copy), List.of());
        }
        List<String> masked = new ArrayList<>();
        for (String code : scope.restrictedPropertyCodes()) {
            if (copy.containsKey(code)) {
                copy.put(code, null);
                masked.add(code);
            }
        }
        masked.sort(null);
        return new MaskedProperties(Collections.unmodifiableMap(copy), List.copyOf(masked));
    }
}
