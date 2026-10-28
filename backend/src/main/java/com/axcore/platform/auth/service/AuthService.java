package com.axcore.platform.auth.service;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.account.service.AccountService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "로그인 ID 또는 비밀번호가 올바르지 않습니다.";
    private static final String INACTIVE_ACCOUNT_MESSAGE = "잠기거나 비활성화된 계정입니다. 관리자에게 문의해 주세요.";

    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    /** 없는 로그인 ID도 비밀번호를 대조해, 응답 시간으로 계정이 있는지 알 수 없게 한다. */
    private final String dummyPasswordHash;

    public AuthService(AccountService accountService, PasswordEncoder passwordEncoder) {
        this.accountService = accountService;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 로그인 ID와 비밀번호를 확인한다 (API 001). 틀리면 실패 횟수를 올리고, 없는 ID와 같은 메시지로 거절한다. 잠기거나 비활성화된 계정은 비밀번호가 맞아도
     * 거절한다.
     */
    public LoginAccount authenticate(String loginId, String password) {
        Optional<Account> found = accountService.findByLoginId(loginId);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw unauthenticated(INVALID_CREDENTIALS_MESSAGE);
        }
        Account account = found.get();
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            accountService.recordLoginFailure(account.getId());
            throw unauthenticated(INVALID_CREDENTIALS_MESSAGE);
        }
        if (!account.isActive()) {
            throw unauthenticated(INACTIVE_ACCOUNT_MESSAGE);
        }
        accountService.recordLoginSuccess(account.getId());
        return toLoginAccount(account);
    }

    /** API 003. 바뀐 계정 정보(임시 비밀번호 상태가 풀림)를 돌려준다. 세션 갱신은 호출한 쪽이 한다. */
    public LoginAccount changePassword(
            LoginAccount loginAccount, String currentPassword, String newPassword) {
        return toLoginAccount(
                accountService.changePassword(loginAccount.id(), currentPassword, newPassword));
    }

    /** 세션의 계정을 DB에서 다시 읽는다 (API 002). 계정이 없어졌으면 인증 실패로 본다. */
    public AccountResponse getMyAccount(LoginAccount loginAccount) {
        return accountService
                .findAccount(loginAccount.id())
                .orElseThrow(() -> unauthenticated(CommonErrorCode.UNAUTHENTICATED.message()));
    }

    private static LoginAccount toLoginAccount(Account account) {
        return new LoginAccount(
                account.getId(),
                account.getLoginId(),
                account.getRole().getCode().name(),
                account.isMustChangePassword());
    }

    private static BusinessException unauthenticated(String message) {
        return new BusinessException(CommonErrorCode.UNAUTHENTICATED, message, Map.of());
    }
}
