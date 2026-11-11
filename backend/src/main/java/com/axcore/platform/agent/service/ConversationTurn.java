package com.axcore.platform.agent.service;

/**
 * 같은 대화의 한 턴(147). 사용자 질문과 그에 대한 Agent 답변이다. 답변을 끝내지 못했으면(실패) 안내 문구만 있어 answer는 null이다. 질문 해석에만 참고로
 * 넘기고 답변을 쓰는 AI에는 넘기지 않는다. 이전 답변의 숫자를 다시 쓰지 않게 하기 위해서다.
 */
public record ConversationTurn(String question, String answer) {}
