package com.axcore.platform.check.domain;

/**
 * 후보 검사(CANDIDATE)를 받는 후보 종류(Jira 338 후속).
 *
 * <p>원천 행으로 시험할 수 있는 것은 연결 규칙(LINK_RULE)뿐이다. 연결 규칙은 저장·수정 때 후보 검사를 접수하고, 승인하려면 같은 개정의 성공한 검사가 있어야
 * 한다. 나머지 종류(객체 유형·속성·관계 유형·매핑·문서 연결)는 시험할 원천 행 비교가 없으므로 후보 검사를 접수하지 않고, 승인 때 검사 결과를 요구하지 않는다.
 */
public final class CandidateCheckTarget {

    private CandidateCheckTarget() {}

    /** 이 종류의 후보가 후보 검사를 받고, 승인에 성공한 검사가 필요한가. */
    public static boolean requiresCheck(String itemKind) {
        return "LINK_RULE".equals(itemKind);
    }
}
