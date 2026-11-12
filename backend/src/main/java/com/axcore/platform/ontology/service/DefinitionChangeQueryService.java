package com.axcore.platform.ontology.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.dto.DefinitionChangeResponse;
import com.axcore.platform.ontology.dto.DefinitionChangeSearchRequest;
import com.axcore.platform.ontology.repository.DefinitionChangeQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 044 정의 변경 이력(Jira 187, FN-ONT-07). 정의 테이블의 변경 전후를 최근 실행부터 돌려준다. 직접 편집(V18 관리 Action)과 후보 승인
 * (DECIDE_PROPOSAL)이 남긴 변경이 함께 나오고 actionCode로 구분한다. 권한 확인은 컨트롤러가 한다(ADMIN).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefinitionChangeQueryService {

    private final DefinitionChangeQueryRepository repository;

    public Page<DefinitionChangeResponse> search(
            DefinitionChangeSearchRequest request, Pageable pageable) {
        if (request.tableName() != null
                && !DefinitionChangeQueryRepository.DEFINITION_TABLES.contains(
                        request.tableName())) {
            throw BusinessException.invalidField(
                    "tableName",
                    "정의 테이블 이름이어야 합니다: "
                            + String.join(", ", DefinitionChangeQueryRepository.DEFINITION_TABLES));
        }
        if (request.rowId() != null && request.tableName() == null) {
            throw BusinessException.invalidField("rowId", "rowId는 tableName과 함께 보내 주세요.");
        }
        if (request.from() != null
                && request.to() != null
                && !request.from().isBefore(request.to())) {
            throw BusinessException.invalidField("from", "from은 to보다 앞이어야 합니다.");
        }
        return repository.search(
                request.tableName(), request.rowId(), request.from(), request.to(), pageable);
    }
}
