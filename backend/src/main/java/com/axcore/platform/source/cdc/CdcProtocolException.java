package com.axcore.platform.source.cdc;

/**
 * 원천 토픽 메시지가 약속한 모양이나 순서와 다르다(Jira 198-2). 소비자는 위치를 옮기지 않고 멈춘다. 메시지에는 원천 행 값을 넣지 않는다(트랜잭션 번호, 위치,
 * 건수만).
 */
public class CdcProtocolException extends RuntimeException {

    public CdcProtocolException(String message) {
        super(message);
    }

    public CdcProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
