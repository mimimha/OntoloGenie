package com.axcore.platform.audit.service;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 감사 기록에 넣을 Action 정의 ID를 코드로 찾는다. 관리 Action(CREATE_ACCOUNT 등)은 도메인 API가 직접 실행하고, 정의는 감사 기록을 연결하려고
 * Flyway 초기 데이터로만 둔다(Action 정의서 2절).
 */
@Service
@RequiredArgsConstructor
public class ActionDefinitionLookup {

    private final ActionDefinitionRepository definitions;

    /** 정의가 없으면 초기 데이터가 빠진 배포라서 요청을 처리하지 않는다. */
    public Long idOf(String code) {
        return definitions
                .findByCode(code)
                .map(ActionDefinition::getId)
                .orElseThrow(() -> new IllegalStateException("Action 정의가 없습니다: " + code));
    }
}
