package com.axcore.platform.auth.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.auth.repository.DataScopeRepository;
import com.axcore.platform.global.security.LoginAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 객체·민감 속성 조회 권한(S15P31C106-205). 객체를 돌려주는 조회는 먼저 {@link #of}로 사용자의 범위를 받고, 그 범위로 거르고 가린다. 기준은
 * {@link DataAccess}에 적었다.
 */
@Service
@RequiredArgsConstructor
public class DataAccessPolicy {

    private final DataScopeRepository dataScopeRepository;

    @Transactional(readOnly = true)
    public DataAccess of(LoginAccount actor) {
        boolean admin = RoleCode.ADMIN.name().equals(actor.roleCode());
        return new DataAccess(dataScopeRepository.findAll(actor.roleCode(), admin));
    }

    /**
     * 내부 서비스 토큰으로 부른 요청의 범위. 모든 유형과 민감 속성을 본다. 지금 내부 API를 부르는 곳은 AI 분석(FastAPI)뿐이고, 분석은 ADMIN만 요청할
     * 수 있어 ADMIN 범위와 같게 둔다(API 043). 사용자를 대신해 부르는 내부 호출이 생기면 그 사용자의 {@link #of}를 쓴다.
     */
    @Transactional(readOnly = true)
    public DataAccess internalService() {
        return new DataAccess(dataScopeRepository.findAll(RoleCode.ADMIN.name(), true));
    }
}
