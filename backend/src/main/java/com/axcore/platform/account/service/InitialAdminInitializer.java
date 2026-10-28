package com.axcore.platform.account.service;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.Role;
import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.account.repository.AccountRepository;
import com.axcore.platform.account.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자가 한 명도 없으면 서버가 시작될 때 첫 관리자를 만든다(D02). 공개 가입이 없어 계정 생성 API를 쓰려면 관리자가 먼저 있어야 하기 때문이다. 만든 계정은 임시
 * 비밀번호 상태라 첫 로그인 뒤 비밀번호를 바꿔야 한다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InitialAdminProperties.class)
@RequiredArgsConstructor
public class InitialAdminInitializer implements ApplicationRunner {

    private final InitialAdminProperties properties;
    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    // 트랜잭션으로 묶지 않는다. 저장이 제약 위반으로 실패하면 묶인 트랜잭션 전체가 롤백 전용이 되기 때문이다.
    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.existsByRole_Code(RoleCode.ADMIN)) {
            return;
        }
        if (!properties.isConfigured()) {
            log.warn(
                    "관리자 계정이 없습니다. AXCORE_INITIAL_ADMIN_LOGIN_ID와 AXCORE_INITIAL_ADMIN_PASSWORD를 넣고 다시 시작하세요.");
            return;
        }
        Role admin =
                roleRepository
                        .findByCode(RoleCode.ADMIN)
                        .orElseThrow(() -> new IllegalStateException("ADMIN 역할이 없습니다."));
        try {
            accountRepository.saveAndFlush(
                    Account.create(
                            properties.loginId(),
                            properties.displayName(),
                            passwordEncoder.encode(properties.password()),
                            admin));
            log.info("첫 관리자 계정을 만들었습니다: loginId={}", properties.loginId());
        } catch (DataIntegrityViolationException ex) {
            // 여러 인스턴스가 동시에 시작하면 다른 인스턴스가 먼저 만들 수 있다.
            log.info("첫 관리자 계정을 다른 인스턴스가 먼저 만들었습니다: loginId={}", properties.loginId());
        }
    }
}
