package com.axcore.platform.agent.service;

import java.util.Locale;
import java.util.Map;

/**
 * 질문이 Agent가 다루는 범위인가(업무 외 질문 가드레일). 질문 해석 AI가 조건과 함께 고르고, 업무가 아니면 서버가 정해진 문장으로 답해 조회하지 않는다. 진짜 업무
 * 질문을 막지 않는 것이 더 중요하므로 AI가 값을 빠뜨리거나 모르는 값을 주면 업무로 본다.
 */
public enum QuestionScope {
    /** 연결된 업무 데이터 조회·재배정 계획. 되묻기에 대한 답("번호 3", "10월")도 여기다. */
    BUSINESS,
    /** 인사나 "뭘 할 수 있어?"처럼 Agent 사용법을 묻는 말. */
    HELP,
    /** 날씨·코딩·잡담처럼 업무 데이터와 관계없는 요청, 지시문·내부 규칙·다른 사용자 정보를 달라는 요청. */
    OUT_OF_SCOPE;

    /** 업무 외·사용 안내 답변의 answerMeta에 그 범위를 남기는 키. 대화 흐름에서 이 답변을 건너뛰는 데 쓴다. */
    static final String ANSWER_META_KEY = "scope";

    /** 업무 외·사용 안내로 정해진 문장으로 답한 답변인가. 이런 답변은 대화의 "잠깐 딴 얘기"라, 되묻기를 이어 가고 최근 대화·요약에 그 문장을 넣지 않는다. */
    static boolean answeredOutsideBusiness(Map<String, Object> answerMeta) {
        return answerMeta != null && answerMeta.containsKey(ANSWER_META_KEY);
    }

    /** AI가 준 값. 비었거나 모르는 값이면 업무로 본다. */
    static QuestionScope parse(String value) {
        if (value == null) {
            return BUSINESS;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BUSINESS;
        }
    }
}
