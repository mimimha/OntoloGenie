package com.axcore.platform.account.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 사용자가 정하는 비밀번호 규칙 (API 003). 8자 이상, 영문·숫자·특수문자를 모두 포함, 로그인 ID와 다름. BCrypt는 72바이트까지만 저장하므로 그보다 길면
 * 거절한다.
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    /** 규칙을 어긴 이유. 지키면 빈 값이다. */
    public static Optional<String> violation(String loginId, String password) {
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of("8자 이상이어야 합니다.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("72바이트(영문 72자)를 넘을 수 없습니다.");
        }
        if (!password.matches(".*[A-Za-z].*")
                || !password.matches(".*[0-9].*")
                || !password.matches(".*\\p{Punct}.*")) {
            return Optional.of("영문·숫자·특수문자를 모두 포함해야 합니다.");
        }
        if (password.equals(loginId)) {
            return Optional.of("로그인 ID와 같을 수 없습니다.");
        }
        return Optional.empty();
    }
}
