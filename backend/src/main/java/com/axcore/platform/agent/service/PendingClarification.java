package com.axcore.platform.agent.service;

/** 마지막 답변이 되묻기였다(143). 이번 질문은 그 답이므로 되물은 문장과 그때까지 알아낸 조건을 함께 넘긴다. */
public record PendingClarification(String askedQuestion, QuestionInterpretation previous) {}
