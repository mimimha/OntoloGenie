package com.axcore.platform.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class LoginAccountAuditorAwareTests {

    private final LoginAccountAuditorAware auditorAware = new LoginAccountAuditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsLoginAccountId() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new LoginAccount(7L, "admin01", "ADMIN", false).toAuthentication());

        assertThat(auditorAware.getCurrentAuditor()).contains(7L);
    }

    @Test
    void isEmptyWithoutLogin() {
        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }

    @Test
    void isEmptyForOtherPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("system", null, null));

        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }
}
