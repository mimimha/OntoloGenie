package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 질문 해석에 넘길 최근 대화 원문을 고른다(147). 턴 수 상한과 토큰 상한을 함께 지킨다.
 *
 * <ul>
 *   <li>최근 maxTurns 턴까지만 넣는다(기본 4, 334 비교 실험으로 확정).
 *   <li>합계가 maxTokens를 넘으면 오래된 턴부터 뺀다.
 *   <li>가장 최근 턴 하나만으로도 넘으면 요청이 실패하지 않게 답변부터 잘라 넣는다.
 * </ul>
 *
 * <p>토큰은 글자 수로 어림한다. 글자 하나를 한 토큰으로 보고, 턴을 감싸는 태그 글자도 함께 센다. 모델 토크나이저와 정확히 같지는 않은 어림값이다. 토크나이저
 * 라이브러리는 쓰지 않는다(2026-11-11 결정).
 */
@Component
public class RecentTurns {
    static final String CUT_MARK = "…(이하 생략)";

    /** 한 턴을 감싸는 태그 글자 수(QuestionInterpreter.describeTurns의 turn·user·agent 태그와 줄바꿈 45자). */
    static final int TURN_OVERHEAD = 45;

    private final int maxTurns;
    private final int maxTokens;

    public RecentTurns(
            @Value("${axcore.agent.history.max-turns:4}") int maxTurns,
            @Value("${axcore.agent.history.max-tokens:2000}") int maxTokens) {
        this.maxTurns = Math.max(0, maxTurns);
        this.maxTokens = Math.max(0, maxTokens);
    }

    /** 최근 대화 원문을 넘기는가. 턴 수나 토큰 상한을 0으로 두면 끈다. */
    public boolean enabled() {
        return maxTurns > 0 && maxTokens > 0;
    }

    /** 고를 때 읽을 최근 메시지 수. 한 턴이 질문·답변 두 메시지라 넉넉히 읽는다. */
    public int messagesToRead() {
        return maxTurns * 2 + 2;
    }

    /** 대화 메시지를 오래된 것부터 받아 넘길 턴을 오래된 것부터 돌려준다. 처리 중인 답변은 넣지 않는다. */
    public List<ConversationTurn> pick(List<AgentMessage> oldestFirst) {
        return picked(oldestFirst).stream().map(StartedTurn::turn).toList();
    }

    /**
     * pick이 원문으로 넘길 턴 중 가장 오래된 턴의 질문 메시지 ID. 오래된 대화 요약은 이 앞의 메시지만 다뤄 원문과 요약 사이에 빠지는 턴이 없게 한다(333).
     * 원문으로 넘길 턴이 없으면 null이다.
     */
    public Long firstPickedMessageId(List<AgentMessage> oldestFirst) {
        List<StartedTurn> picked = picked(oldestFirst);
        return picked.isEmpty() ? null : picked.getFirst().questionId();
    }

    /** 메시지를 턴으로 묶는다. 처리 중인 답변은 넣지 않고, 실패한 답변은 질문만 넣는다. 오래된 대화 요약도 같은 턴을 쓴다(333). */
    static List<ConversationTurn> turnsOf(List<AgentMessage> oldestFirst) {
        return toTurns(oldestFirst).stream().map(StartedTurn::turn).toList();
    }

    /** 턴과 그 턴을 시작한 질문 메시지 ID. */
    private record StartedTurn(Long questionId, ConversationTurn turn) {}

    private List<StartedTurn> picked(List<AgentMessage> oldestFirst) {
        List<StartedTurn> turns = toTurns(oldestFirst);
        List<StartedTurn> picked = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0 && picked.size() < maxTurns; i--) {
            StartedTurn turn = turns.get(i);
            int size = tokens(turn.turn());
            if (used + size <= maxTokens) {
                picked.add(turn);
                used += size;
                continue;
            }
            if (picked.isEmpty()) {
                // 가장 최근 턴만으로도 상한을 넘으면 잘라서라도 넣는다. 질문은 최대한 남기고 답변부터 자른다.
                ConversationTurn cut = cut(turn.turn(), maxTokens);
                if (cut != null) {
                    picked.add(new StartedTurn(turn.questionId(), cut));
                }
            }
            break;
        }
        Collections.reverse(picked);
        return picked;
    }

    private static List<StartedTurn> toTurns(List<AgentMessage> oldestFirst) {
        List<StartedTurn> turns = new ArrayList<>();
        AgentMessage question = null;
        for (AgentMessage message : oldestFirst) {
            if (message.getSender() == MessageSender.USER) {
                if (question != null) {
                    turns.add(questionOnly(question));
                }
                question = message;
                continue;
            }
            if (question == null || message.getStatus() == MessageStatus.RUNNING) {
                continue;
            }
            // 실패한 답변은 안내 문구뿐이라 질문만 넣는다.
            String answer =
                    message.getStatus() == MessageStatus.SUCCEEDED ? message.getContent() : null;
            turns.add(
                    new StartedTurn(
                            question.getId(), new ConversationTurn(question.getContent(), answer)));
            question = null;
        }
        if (question != null) {
            turns.add(questionOnly(question));
        }
        return turns;
    }

    private static StartedTurn questionOnly(AgentMessage question) {
        return new StartedTurn(question.getId(), new ConversationTurn(question.getContent(), null));
    }

    static int tokens(ConversationTurn turn) {
        return TURN_OVERHEAD + length(turn.question()) + length(turn.answer());
    }

    /** 태그 몫을 뺀 budget 안에 들도록 자른다. 질문조차 남길 수 없으면 null. */
    private static ConversationTurn cut(ConversationTurn turn, int maxTokens) {
        int budget = maxTokens - TURN_OVERHEAD;
        int questionLength = length(turn.question());
        if (questionLength >= budget) {
            String question = shorten(turn.question(), budget);
            return question == null ? null : new ConversationTurn(question, null);
        }
        String answer = shorten(turn.answer(), budget - questionLength);
        return new ConversationTurn(turn.question(), answer);
    }

    /** 글자 수가 budget 이하가 되게 앞부분만 남기고 생략 표시를 붙인다. 남길 수 없으면 null. */
    private static String shorten(String text, int budget) {
        if (text == null) {
            return null;
        }
        if (length(text) <= budget) {
            return text;
        }
        int keep = budget - length(CUT_MARK);
        if (keep <= 0) {
            return null;
        }
        return text.substring(0, text.offsetByCodePoints(0, keep)) + CUT_MARK;
    }

    private static int length(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
