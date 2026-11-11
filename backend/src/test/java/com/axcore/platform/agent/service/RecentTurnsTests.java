package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.agent.domain.AgentMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecentTurnsTests {
    private static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");

    @Test
    void keepsOnlyNewestTurnsUpToMaxTurns() {
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            messages.addAll(answered("질문 " + i, "답변 " + i));
        }

        List<ConversationTurn> turns = new RecentTurns(4, 2000).pick(messages);

        assertThat(turns)
                .extracting(ConversationTurn::question)
                .containsExactly("질문 3", "질문 4", "질문 5", "질문 6");
    }

    @Test
    void dropsOldestTurnsWhenTokenBudgetIsExceeded() {
        List<AgentMessage> messages = new ArrayList<>();
        messages.addAll(answered("가".repeat(10), "나".repeat(40)));
        messages.addAll(answered("다".repeat(10), "라".repeat(40)));
        messages.addAll(answered("마".repeat(10), "바".repeat(40)));

        // 한 턴이 본문 50글자와 태그 45글자라 상한 200이면 최근 두 턴만 들어간다.
        List<ConversationTurn> turns = new RecentTurns(4, 200).pick(messages);

        assertThat(turns).hasSize(2);
        assertThat(turns.getFirst().question()).startsWith("다");
        assertThat(turns).allMatch(turn -> RecentTurns.tokens(turn) == 95);
    }

    @Test
    void keepsTurnUncutWhenItExactlyFillsBudget() {
        List<AgentMessage> messages = answered("가".repeat(5), "나".repeat(50));

        List<ConversationTurn> turns =
                new RecentTurns(4, 55 + RecentTurns.TURN_OVERHEAD).pick(messages);

        assertThat(turns).containsExactly(new ConversationTurn("가".repeat(5), "나".repeat(50)));
    }

    @Test
    void cutsByCodePointsWithoutBreakingSurrogatePairs() {
        List<AgentMessage> messages = answered("질문", "😀".repeat(500));

        List<ConversationTurn> turns = new RecentTurns(4, 100).pick(messages);

        // 본문 몫 55 - 질문 2 = 답변 53, 생략 표시 8글자를 빼면 이모지 45개가 남는다.
        assertThat(turns.getFirst().answer()).isEqualTo("😀".repeat(45) + RecentTurns.CUT_MARK);
    }

    @Test
    void skipsAnswerWithoutQuestionAtStartOfWindow() {
        // 읽은 범위가 답변부터 시작하면 그 답변의 질문은 범위 밖이라 버린다.
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(answered("범위 밖 질문", "앞 답변").get(1));
        messages.addAll(answered("PR-03 상태", "정상입니다."));

        List<ConversationTurn> turns = new RecentTurns(4, 2000).pick(messages);

        assertThat(turns).containsExactly(new ConversationTurn("PR-03 상태", "정상입니다."));
    }

    @Test
    void includesClarificationAnswerSoFollowUpCanPointToCandidates() {
        AgentMessage asked = AgentMessage.question(1L, "06번 이력은?", Map.of(), NOW);
        AgentMessage clarified = AgentMessage.runningAnswer(1L, NOW);
        clarified.succeed("어느 설비인가요? 1. CNC 6호기 2. 프레스 6호기", Map.of("clarification", Map.of()));

        List<ConversationTurn> turns = new RecentTurns(4, 2000).pick(List.of(asked, clarified));

        assertThat(turns)
                .containsExactly(
                        new ConversationTurn("06번 이력은?", "어느 설비인가요? 1. CNC 6호기 2. 프레스 6호기"));
    }

    @Test
    void cutsNewestTurnInsteadOfFailingWhenItAloneExceedsBudget() {
        List<AgentMessage> messages = answered("PR-03 실행 이력 알려줘", "가".repeat(500));

        List<ConversationTurn> turns = new RecentTurns(4, 100).pick(messages);

        // 질문은 남기고 답변을 잘라 상한 안에 넣는다.
        assertThat(turns).hasSize(1);
        ConversationTurn turn = turns.getFirst();
        assertThat(turn.question()).isEqualTo("PR-03 실행 이력 알려줘");
        assertThat(turn.answer()).endsWith(RecentTurns.CUT_MARK);
        assertThat(RecentTurns.tokens(turn)).isLessThanOrEqualTo(100);
    }

    @Test
    void cutsQuestionTooWhenQuestionAloneExceedsBudget() {
        List<AgentMessage> messages = answered("가".repeat(300), "답변");

        List<ConversationTurn> turns = new RecentTurns(4, 100).pick(messages);

        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst().answer()).isNull();
        assertThat(RecentTurns.tokens(turns.getFirst())).isLessThanOrEqualTo(100);
    }

    @Test
    void keepsOnlyQuestionForFailedAnswerAndSkipsRunningAnswer() {
        List<AgentMessage> messages = new ArrayList<>();
        AgentMessage question = AgentMessage.question(1L, "PR-03 상태", Map.of(), NOW);
        AgentMessage failed = AgentMessage.runningAnswer(1L, NOW);
        failed.fail("답변을 끝내지 못했습니다.", "AI 응답 실패");
        messages.add(question);
        messages.add(failed);
        messages.add(AgentMessage.question(1L, "지금 질문", Map.of(), NOW));
        messages.add(AgentMessage.runningAnswer(1L, NOW));

        List<ConversationTurn> turns = new RecentTurns(4, 2000).pick(messages);

        // 실패한 답변의 안내 문구는 넣지 않는다. 처리 중인 답변은 아직 답이 아니므로 질문만 남는다.
        assertThat(turns)
                .containsExactly(
                        new ConversationTurn("PR-03 상태", null),
                        new ConversationTurn("지금 질문", null));
    }

    @Test
    void tellsWhereRawTurnsStartSoSummaryCoversEverythingBefore() {
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            messages.addAll(answered("질문 " + i, "답변 " + i));
        }
        numbered(messages, 11);

        // 4턴이면 질문 3(메시지 15)부터 원문이다. 요약은 그 앞(메시지 11~14)까지 다룬다(333).
        assertThat(new RecentTurns(4, 2000).firstPickedMessageId(messages)).isEqualTo(15L);
        // 토큰 상한에 걸려 2턴만 원문이면 질문 5(메시지 19)부터다. 원문과 요약 사이에 빠지는 턴이 없다.
        assertThat(new RecentTurns(4, 2 * (45 + 8)).firstPickedMessageId(messages)).isEqualTo(19L);
        assertThat(new RecentTurns(0, 2000).firstPickedMessageId(messages)).isNull();
    }

    @Test
    void buildsSummaryTurnsLikeRawTurns() {
        List<AgentMessage> messages = new ArrayList<>(answered("질문", "답변"));
        AgentMessage failedQuestion = AgentMessage.question(1L, "실패한 질문", Map.of(), NOW);
        AgentMessage failed = AgentMessage.runningAnswer(1L, NOW);
        failed.fail("답변을 끝내지 못했습니다.", "AI 응답 실패");
        messages.add(failedQuestion);
        messages.add(failed);

        assertThat(RecentTurns.turnsOf(messages))
                .containsExactly(
                        new ConversationTurn("질문", "답변"), new ConversationTurn("실패한 질문", null));
    }

    private static void numbered(List<AgentMessage> messages, long firstId) {
        for (int i = 0; i < messages.size(); i++) {
            ReflectionTestUtils.setField(messages.get(i), "id", firstId + i);
        }
    }

    @Test
    void returnsNothingWhenDisabled() {
        RecentTurns disabled = new RecentTurns(0, 2000);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.pick(answered("질문", "답변"))).isEmpty();
    }

    private static List<AgentMessage> answered(String question, String answer) {
        AgentMessage asked = AgentMessage.question(1L, question, Map.of(), NOW);
        AgentMessage answered = AgentMessage.runningAnswer(1L, NOW);
        answered.succeed(answer, Map.of());
        return List.of(asked, answered);
    }
}
