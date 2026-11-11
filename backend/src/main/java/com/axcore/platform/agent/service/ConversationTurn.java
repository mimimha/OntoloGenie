package com.axcore.platform.agent.service;

/**
 * 같은 대화의 한 턴(147). 사용자 질문과 그에 대한 Agent 답변이다. 답변을 끝내지 못했으면(실패) 안내 문구만 있어 answer는 null이다. 질문 해석에만 참고로
 * 넘기고 답변을 쓰는 AI에는 넘기지 않는다. 이전 답변의 숫자를 다시 쓰지 않게 하기 위해서다.
 *
 * <p>order는 이 턴이 대화 전체에서 몇 번째 질문인지다(1부터). "두 번째로 물어본 설비"처럼 질문 순서로 가리키는 말을 최근 대화와 오래된 대화 요약에 걸쳐 같은
 * 기준으로 세게 한다(333). 모르면 null이다.
 */
public record ConversationTurn(String question, String answer, Integer order) {

    /** 순번을 모르는 턴. 메시지에서 턴을 고를 때 먼저 이렇게 만들고 {@link #numbered}로 순번을 붙인다. */
    public ConversationTurn(String question, String answer) {
        this(question, answer, null);
    }

    /** 이 턴에 대화 전체 기준 순번을 붙인다. */
    public ConversationTurn numbered(int order) {
        return new ConversationTurn(question, answer, order);
    }
}
