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
}
