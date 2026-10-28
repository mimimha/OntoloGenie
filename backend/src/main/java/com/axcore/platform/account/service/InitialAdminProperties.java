package com.axcore.platform.account.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첫 관리자 계정 값. 환경변수 AXCORE_INITIAL_ADMIN_LOGIN_ID, AXCORE_INITIAL_ADMIN_PASSWORD로 넣는다. 비밀번호는 저장소에 두지
 * 않는다.
 */
@ConfigurationProperties("axcore.initial-admin")
public record InitialAdminProperties(String loginId, String password, String displayName) {

    boolean isConfigured() {
        return loginId != null && !loginId.isBlank() && password != null && !password.isBlank();
    }
}
