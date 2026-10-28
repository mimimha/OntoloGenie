package com.axcore.platform.account.service;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.dto.AccountResponse;
import com.axcore.platform.account.repository.AccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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

    /** 로그인 확인용. 역할을 함께 읽는다. */
    public Optional<Account> findByLoginId(String loginId) {
        return accountRepository.findByLoginId(loginId);
    }

    public Optional<AccountResponse> findAccount(Long id) {
        return accountRepository.findWithRoleById(id).map(AccountResponse::from);
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
