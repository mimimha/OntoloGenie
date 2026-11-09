package com.axcore.platform.source.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import com.axcore.platform.source.cdc.CdcMessage.Heartbeat;
import com.axcore.platform.source.cdc.CdcMessage.Operation;
import com.axcore.platform.source.cdc.CdcMessage.TransactionBoundary;
import com.axcore.platform.source.cdc.SourceTransactionAssembler.TooLargeTransactionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 원천 토픽 메시지 해석과 원천 트랜잭션 조립(Jira 198-2). 시험 자료는 Debezium 3.6.3에서 받은 실제 메시지 37건이다 ({@code
 * cdc-tx/README.md}).
 */
class SourceTransactionAssemblerTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CdcMessageParser PARSER = new CdcMessageParser(JSON);

    private static List<String> lines;
    private static List<CdcMessage> messages;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in =
                SourceTransactionAssemblerTests.class
                        .getClassLoader()
                        .getResourceAsStream("cdc-tx/debezium-tx-messages.jsonl")) {
            lines =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(l -> !l.isBlank())
                            .toList();
        }
        messages = lines.stream().map(PARSER::parse).toList();
    }

    @Test
    void parsesEveryRealMessage() {
        assertThat(messages).hasSize(37);
        assertThat(messages.stream().filter(Heartbeat.class::isInstance)).hasSize(12);
        assertThat(messages.stream().filter(TransactionBoundary.class::isInstance)).hasSize(14);
        List<ChangeEvent> changes =
                messages.stream()
                        .filter(ChangeEvent.class::isInstance)
                        .map(ChangeEvent.class::cast)
                        .toList();
        assertThat(changes).hasSize(11);
        ChangeEvent first = changes.getFirst();
        assertThat(first.op()).isEqualTo(Operation.CREATE);
        assertThat(first.resourceKey()).isEqualTo("erp.equipment");
        assertThat(first.txId()).isEqualTo(756);
        assertThat(first.lsn()).isEqualTo(29322120L);
        assertThat(first.before()).isNull();
        assertThat(first.after().get("name").asString()).isEqualTo("EQ-3");
        assertThat(first.schema()).isNotNull();
        assertThat(first.processedTimeMs()).isGreaterThanOrEqualTo(first.commitTimeMs());
        assertThat(changes.getLast().op()).isEqualTo(Operation.TRUNCATE);
        assertThat(changes.getLast().after()).isNull();
    }

    @Test
    void assemblesTransactionsInCommitOrder() {
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(100);
        List<SourceTransaction> done = feedAll(assembler);

        assertThat(done)
                .extracting(SourceTransaction::txId)
                .containsExactly(756L, 757L, 758L, 761L, 762L, 763L, 765L);
        assertThat(done).extracting(t -> t.events().size()).containsExactly(3, 1, 2, 2, 1, 1, 1);
        // 첫 트랜잭션 중간에 heartbeat가 끼어 있었지만 경계로 보지 않는다.
        SourceTransaction first = done.getFirst();
        assertThat(lines.subList((int) first.beginOffset(), (int) first.endOffset() + 1))
                .anyMatch(l -> PARSER.parse(l) instanceof Heartbeat);
        // PK 변경은 같은 트랜잭션의 옛 키 삭제 + 새 키 추가로 온다.
        assertThat(done.get(3).events())
                .extracting(ChangeEvent::op)
                .containsExactly(Operation.DELETE, Operation.CREATE);
        assertThat(done.get(3).events().get(0).before().get("id").asLong()).isEqualTo(3);
        assertThat(done.get(3).events().get(1).after().get("id").asLong()).isEqualTo(30);
        assertThat(done.get(5).events().getFirst().after().get("big").asString())
                .isEqualTo("__debezium_unavailable_value");
        assertThat(done.getLast().nextOffset()).isEqualTo(done.getLast().endOffset() + 1);
        assertThat(assembler.inTransaction()).isFalse();
        assertThat(assembler.resumeOffset()).isEqualTo(37);
    }

    @Test
    void resumeOffsetPointsAtOpenBegin() {
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(100);
        int begin = indexOfBegin(756);
        for (int i = 0; i <= begin + 2; i++) {
            assembler.accept(messages.get(i), i);
        }

        assertThat(assembler.inTransaction()).isTrue();
        assertThat(assembler.resumeOffset()).isEqualTo(begin);
    }

    @Test
    void restartsWhenSameTransactionIsSentAgain() {
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(100);
        int begin = indexOfBegin(756);
        int end = indexOfEnd(756);
        long offset = 0;
        // 앞부분만 보내다가 커넥터가 재시작해 같은 트랜잭션을 처음부터 다시 보낸다.
        for (int i = begin; i < begin + 2; i++) {
            assembler.accept(messages.get(i), offset++);
        }
        Optional<SourceTransaction> done = Optional.empty();
        for (int i = begin; i <= end; i++) {
            done = assembler.accept(messages.get(i), offset++);
        }

        assertThat(done).isPresent();
        assertThat(done.get().events()).hasSize(3);
        assertThat(done.get().beginOffset()).isEqualTo(2);
    }

    @Test
    void rejectsOutOfOrderStreams() {
        int begin = indexOfBegin(756);
        int end = indexOfEnd(756);
        ChangeEvent change = (ChangeEvent) messages.get(begin + 1);

        assertThatThrownBy(() -> new SourceTransactionAssembler(100).accept(change, 0))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("경계 밖");
        assertThatThrownBy(() -> new SourceTransactionAssembler(100).accept(messages.get(end), 0))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("BEGIN 없이");

        SourceTransactionAssembler other = new SourceTransactionAssembler(100);
        other.accept(messages.get(begin), 0);
        assertThatThrownBy(() -> other.accept(messages.get(indexOfBegin(757)), 1))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("END 없이");

        SourceTransactionAssembler backwards = new SourceTransactionAssembler(100);
        backwards.accept(messages.get(begin), 5);
        assertThatThrownBy(() -> backwards.accept(change, 4))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("뒤로");
    }

    @Test
    void rejectsEndWhoseCountDoesNotMatch() {
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(100);
        int begin = indexOfBegin(756);
        int end = indexOfEnd(756);
        long offset = 0;
        boolean skipped = false;
        for (int i = begin; i < end; i++) {
            if (!skipped && messages.get(i) instanceof ChangeEvent) {
                skipped = true; // 변경 하나를 잃었다고 본다
                continue;
            }
            assembler.accept(messages.get(i), offset++);
        }
        long endOffset = offset;

        assertThatThrownBy(() -> assembler.accept(messages.get(end), endOffset))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("END와 다릅니다");
    }

    @Test
    void stopsTooLargeTransaction() {
        SourceTransactionAssembler assembler = new SourceTransactionAssembler(2);
        int begin = indexOfBegin(756);

        assertThatThrownBy(
                        () -> {
                            for (int i = begin; i <= indexOfEnd(756); i++) {
                                assembler.accept(messages.get(i), i);
                            }
                        })
                .isInstanceOf(TooLargeTransactionException.class)
                .satisfies(
                        e -> assertThat(((TooLargeTransactionException) e).txId()).isEqualTo(756));
    }

    @Test
    void parserRejectsMessagesOutsideTheContract() {
        ObjectNode change = (ObjectNode) JSON.readTree(lines.get(indexOfBegin(756) + 1));
        ObjectNode payload = (ObjectNode) change.get("payload");

        payload.put("op", "r");
        assertThatThrownBy(() -> PARSER.parse(change.toString()))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("snapshot.mode");

        payload.put("op", "c");
        payload.remove("transaction");
        assertThatThrownBy(() -> PARSER.parse(change.toString()))
                .isInstanceOf(CdcProtocolException.class)
                .hasMessageContaining("provide.transaction.metadata");

        assertThatThrownBy(() -> PARSER.parse(null)).isInstanceOf(CdcProtocolException.class);
        assertThatThrownBy(() -> PARSER.parse("{\"payload\": {\"x\": 1}}"))
                .isInstanceOf(CdcProtocolException.class);
        // 값은 메시지에 남기지 않는다.
        assertThatThrownBy(() -> PARSER.parse("{\"payload\": {\"secret\": \"EQ-3\"}}"))
                .hasMessageNotContaining("EQ-3");
    }

    @Test
    void parsesMessagesWithoutSchemaEnvelope() {
        String payloadOnly =
                JSON.readTree(lines.get(indexOfBegin(756) + 1)).get("payload").toString();

        CdcMessage parsed = PARSER.parse(payloadOnly);

        assertThat(parsed).isInstanceOf(ChangeEvent.class);
        assertThat(((ChangeEvent) parsed).schema()).isNull();
    }

    private static List<SourceTransaction> feedAll(SourceTransactionAssembler assembler) {
        List<SourceTransaction> done = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            assembler.accept(messages.get(i), i).ifPresent(done::add);
        }
        return done;
    }

    private static int indexOfBegin(long txId) {
        return indexOf(txId, CdcMessage.BoundaryStatus.BEGIN);
    }

    private static int indexOfEnd(long txId) {
        return indexOf(txId, CdcMessage.BoundaryStatus.END);
    }

    private static int indexOf(long txId, CdcMessage.BoundaryStatus status) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof TransactionBoundary b
                    && b.txId() == txId
                    && b.status() == status) {
                return i;
            }
        }
        throw new AssertionError("경계 없음 " + txId + " " + status);
    }
}
