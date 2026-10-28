package com.axcore.platform.account.domain;

/** 계정 상태. ACTIVE만 로그인할 수 있다. */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
