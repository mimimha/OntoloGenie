package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectTypeOptionResponse;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 객체 검색 조건의 유형 선택지(S15P31C106-347). 객체 검색(076)과 같은 권한 범위({@link DataAccessPolicy})로 볼 수 있는 유형만
 * 돌려준다. 유형별 객체 수·관계는 모델 관계도(042)가 맡고 여기서는 계산하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ObjectTypeOptionService {

    private final DataAccessPolicy dataAccessPolicy;
    private final ObjectTypeRepository objectTypes;

    @Transactional(readOnly = true)
    public List<ObjectTypeOptionResponse> options(LoginAccount actor) {
        Set<Long> visibleTypeIds = dataAccessPolicy.of(actor).visibleObjectTypeIds();
        if (visibleTypeIds.isEmpty()) {
            return List.of();
        }
        return objectTypes.findByIdInOrderByNameAscIdAsc(visibleTypeIds).stream()
                .map(
                        type ->
                                new ObjectTypeOptionResponse(
                                        type.getId(), type.getCode(), type.getName()))
                .toList();
    }
}
