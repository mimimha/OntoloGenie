package com.axcore.platform.auth.controller;

import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.auth.dto.LoginRequest;
import com.axcore.platform.auth.service.AuthService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    /** API 001. 세션 쿠키와 XSRF-TOKEN 쿠키를 새로 발급한다. */
    @PostMapping("/login")
    public ApiResponse<AccountResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        LoginAccount loginAccount = authService.authenticate(request.loginId(), request.password());
        startSession(loginAccount, httpRequest, httpResponse);
        return ApiResponse.of(authService.getMyAccount(loginAccount));
    }

    /** API 002. 새로고침 뒤 XSRF-TOKEN 쿠키가 없으면 여기서 다시 발급한다. */
    @GetMapping("/me")
    public ApiResponse<AccountResponse> me(
            @AuthenticationPrincipal LoginAccount loginAccount, CsrfToken csrfToken) {
        csrfToken.getToken();
        return ApiResponse.of(authService.getMyAccount(loginAccount));
    }

    /** API 099. 이 세션만 지운다. 같은 계정의 다른 세션은 그대로 둔다. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        csrfTokenRepository.saveToken(null, request, response);
    }

    private void startSession(
            LoginAccount loginAccount, HttpServletRequest request, HttpServletResponse response) {
        // 로그인 전에 쓰던 세션 ID는 버린다(세션 고정 방지).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(loginAccount.toAuthentication());
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(
                csrfTokenRepository.generateToken(request), request, response);
    }
}
