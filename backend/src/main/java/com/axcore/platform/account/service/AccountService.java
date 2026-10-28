package com.axcore.platform.account.service;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.account.repository.AccountRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    /** 연속으로 이만큼 틀리면 계정을 잠근다 (API 001, D3). */
    public static final int MAX_LOGIN_FAILURES = 5;

    private final AccountRepository accountRepository;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;

    /** 로그인 확인용. 역할을 함께 읽는다. */
    public Optional<Account> findByLoginId(String loginId) {
        return accountRepository.findByLoginId(loginId);
    }

    public Optional<AccountResponse> findAccount(Long id) {
        return accountRepository.findWithRoleById(id).map(AccountResponse::from);
    }

    /**
     * 본인 비밀번호를 바꾼다 (API 003). 현재 비밀번호가 맞아야 하고, 새 비밀번호는 {@link PasswordPolicy}를 지키며 현재 비밀번호와 달라야 한다.
     * 바꾼 계정을 돌려준다.
     */
    @Transactional
    public Account changePassword(Long accountId, String currentPassword, String newPassword) {
        Account account =
                accountRepository
                        .findWithRoleById(accountId)
                        .filter(Account::isActive)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw BusinessException.invalidField("currentPassword", "현재 비밀번호가 올바르지 않습니다.");
        }
        PasswordPolicy.violation(account.getLoginId(), newPassword)
                .ifPresent(
                        reason -> {
                            throw BusinessException.invalidField("newPassword", reason);
                        });
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw BusinessException.invalidField("newPassword", "현재 비밀번호와 같을 수 없습니다.");
        }
        account.changePassword(passwordEncoder.encode(newPassword), Instant.now(clock));
        return account;
    }

    @Transactional
    public void recordLoginFailure(Long id) {
        accountRepository.recordLoginFailure(id, MAX_LOGIN_FAILURES);
    }

    @Transactional
    public void recordLoginSuccess(Long id) {
        accountRepository.recordLoginSuccess(id, Instant.now(clock));
    }
}
