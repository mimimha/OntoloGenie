package com.axcore.platform.global.security;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 공통 Entity의 created_by·updated_by에 로그인 계정 ID를 넣는다. 로그인 사용자가 없는 작업(배치 등)은 빈 값이다. */
@Component
public class LoginAccountAuditorAware implements AuditorAware<Long> {

    @Override
    public Optional<Long> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof LoginAccount account) {
            return Optional.of(account.id());
        }
        return Optional.empty();
    }
}
