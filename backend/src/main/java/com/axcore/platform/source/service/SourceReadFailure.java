package com.axcore.platform.source.service;

import java.sql.SQLException;

/** 드라이버 오류 원문에는 접속 정보나 원천 값이 들어갈 수 있으므로 SQLSTATE로 안내 문구를 고른다. */
public final class SourceReadFailure {

    private SourceReadFailure() {}

    public static String message(SQLException failure) {
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        if (state.equals("28P01") || state.equals("28000")) {
            return "SYNC 계정으로 원천에 로그인하지 못했습니다. 계정과 비밀번호를 확인해 주세요.";
        }
        if (state.startsWith("08")) {
            return "원천에 접속하지 못했습니다. 주소와 네트워크를 확인해 주세요.";
        }
        if (state.equals("42501")) {
            return "SYNC 계정에 원천 테이블이나 컬럼을 읽을 권한이 없습니다. 연결 검사를 다시 실행해 권한을 확인해 주세요.";
        }
        if (state.equals("42P01") || state.equals("42703") || state.equals("42P09")) {
            return "원천에서 테이블이나 컬럼을 찾지 못했습니다. 연결 검사를 다시 실행해 카탈로그를 갱신해 주세요.";
        }
        if (state.equals("57014")) {
            return "원천 테이블을 읽는 시간이 너무 오래 걸려 중단했습니다.";
        }
        return "원천 테이블을 읽지 못했습니다(SQLSTATE %s).".formatted(state.isEmpty() ? "없음" : state);
    }
}
