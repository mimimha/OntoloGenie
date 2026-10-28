package com.axcore.platform.account.dto;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.AccountStatus;
import com.axcore.platform.account.domain.RoleCode;

/** API 명세의 Account (001·002·004·006). 비밀번호 해시는 담지 않는다. */
public record AccountResponse(
        Long id,
        String loginId,
        String displayName,
        RoleCode roleCode,
        AccountStatus status,
        boolean mustChangePassword,
        long rowVersion) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getLoginId(),
                account.getDisplayName(),
                account.getRole().getCode(),
                account.getStatus(),
                account.isMustChangePassword(),
                account.getRowVersion());
    }
}
