package com.axcore.platform.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.account.repository.AccountRepository;
import com.axcore.platform.account.repository.RoleRepository;
import com.axcore.platform.global.config.JpaAuditingConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 관리자가 없을 때만 환경변수 값으로 첫 관리자를 만든다(D02). */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    JpaAuditingConfig.class,
    InitialAdminInitializerTests.ClockConfig.class
})
class InitialAdminInitializerTests {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired AccountRepository accountRepository;

    @Autowired RoleRepository roleRepository;

    @Test
    void createsTemporaryAdminWhenNoAdminExists() {
        initializer(new InitialAdminProperties("admin01", "Init!Passw0rd", "관리자")).run(noArgs());

        Account admin = accountRepository.findByLoginId("admin01").orElseThrow();
        assertThat(admin.getRole().getCode()).isEqualTo(RoleCode.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getCreatedBy()).isNull();
        assertThat(passwordEncoder.matches("Init!Passw0rd", admin.getPasswordHash())).isTrue();
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        initializer(new InitialAdminProperties("admin01", "Init!Passw0rd", "관리자")).run(noArgs());

        initializer(new InitialAdminProperties("admin02", "Other!Passw0rd", "관리자")).run(noArgs());

        assertThat(accountRepository.findByLoginId("admin02")).isEmpty();
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void skipsWhenValuesAreMissing() {
        initializer(new InitialAdminProperties("", "", "관리자")).run(noArgs());

        assertThat(accountRepository.count()).isZero();
    }

    private InitialAdminInitializer initializer(InitialAdminProperties properties) {
        return new InitialAdminInitializer(
                properties, accountRepository, roleRepository, passwordEncoder);
    }

    private static DefaultApplicationArguments noArgs() {
        return new DefaultApplicationArguments();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
