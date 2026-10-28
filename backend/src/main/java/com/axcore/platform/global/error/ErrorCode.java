package com.axcore.platform.global.error;

import org.springframework.http.HttpStatus;

/** 오류 코드. 공통 코드는 {@link CommonErrorCode}에 두고, 도메인 전용 코드는 각 도메인 패키지에 이 인터페이스를 구현한 enum으로 만든다. */
public interface ErrorCode {

    String code();

    HttpStatus status();

    String message();
}
