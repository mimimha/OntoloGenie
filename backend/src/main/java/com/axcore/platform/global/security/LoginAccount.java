package com.axcore.platform.global.security;

import java.io.Serializable;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 세션에 저장하는 로그인 사용자. Controller에서 {@code @AuthenticationPrincipal LoginAccount}로 받는다. 이름(getName)은
 * 계정 ID라서 Spring Session이 계정별로 세션을 찾을 때 쓴다.
 */
public record LoginAccount(Long id, String loginId, String roleCode, boolean mustChangePassword)
        implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return String.valueOf(id);
    }

    public Authentication toAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                this, null, List.of(new SimpleGrantedAuthority("ROLE_" + roleCode)));
    }
}
