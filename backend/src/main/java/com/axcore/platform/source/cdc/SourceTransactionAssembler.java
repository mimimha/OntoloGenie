package com.axcore.platform.source.cdc;

import com.axcore.platform.source.cdc.CdcMessage.BoundaryStatus;
import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import com.axcore.platform.source.cdc.CdcMessage.Heartbeat;
import com.axcore.platform.source.cdc.CdcMessage.TransactionBoundary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 원천 토픽 하나의 메시지를 원천 트랜잭션 단위로 모은다(Jira 198-2, 결정 문서 8장 구현 조건·10장). 소비자(198-5)는 완성된 트랜잭션 하나를 플랫폼 트랜잭션
 * 하나로 반영한다. 그래서 원천 트랜잭션의 중간 상태가 플랫폼에 커밋되지 않는다.
 *
 * <ul>
 *   <li>BEGIN → 그 트랜잭션의 변경들 → END. END의 {@code event_count}와 표별 건수가 모은 변경과 맞아야 완성이다.
 *   <li>heartbeat는 트랜잭션 중간에도 낄 수 있다. 경계로 보지 않는다.
 *   <li>같은 트랜잭션의 BEGIN이 다시 오면(커넥터 재시작으로 처음부터 다시 보냄) 모으던 것을 버리고 다시 모은다.
 *   <li>그 밖에 순서가 어긋나면(END 없이 다른 BEGIN, 경계 밖 변경, 번호·건수 불일치) {@link CdcProtocolException}. 소비자는 위치를
 *       옮기지 않고 멈춘다.
 *   <li>변경이 {@code maxEvents}를 넘으면 {@link TooLargeTransactionException}. 소비자는 그 원천을 복구 상태로 돌린다(부분
 *       상태를 공개하지 않는다).
 * </ul>
 *
 * <p>원천 토픽 파티션 하나에 하나를 쓴다. 스레드에 안전하지 않다.
 */
public class SourceTransactionAssembler {

    private final int maxEvents;
    private Open open;
    private long nextOffset = -1;

    public SourceTransactionAssembler(int maxEvents) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents는 1 이상입니다.");
        }
        this.maxEvents = maxEvents;
    }

    /**
     * 메시지 하나를 받는다. 트랜잭션이 완성되면 돌려준다.
     *
     * @param offset 이 메시지의 Kafka offset(파티션 안에서 늘어난다)
     */
    public Optional<SourceTransaction> accept(CdcMessage message, long offset) {
        Objects.requireNonNull(message, "message");
        if (nextOffset >= 0 && offset < nextOffset) {
            throw new CdcProtocolException(
                    "offset이 뒤로 갔습니다(받은 %d, 다음 기대 %d).".formatted(offset, nextOffset));
        }
        nextOffset = offset + 1;
        return switch (message) {
            case Heartbeat ignored -> Optional.empty();
            case TransactionBoundary boundary when boundary.status() == BoundaryStatus.BEGIN -> {
                begin(boundary, offset);
                yield Optional.empty();
            }
            case TransactionBoundary boundary -> Optional.of(end(boundary, offset));
            case ChangeEvent event -> {
                add(event, offset);
                yield Optional.empty();
            }
        };
    }

    /**
     * 다시 시작할 때 읽기 시작할 offset. 모으는 중인 트랜잭션이 있으면 그 BEGIN, 없으면 마지막으로 받은 메시지 다음이다. 아무것도 받지 않았으면 -1. 소비
     * 위치 저장은 반영을 마친 트랜잭션 뒤로만 하므로, 이 값은 "앞에 덜 끝난 구간이 없는 위치"다.
     */
    public long resumeOffset() {
        return open != null ? open.beginOffset : nextOffset;
    }

    public boolean inTransaction() {
        return open != null;
    }

    private void begin(TransactionBoundary boundary, long offset) {
        if (open != null && open.txId != boundary.txId()) {
            throw new CdcProtocolException(
                    "트랜잭션 %d의 END 없이 트랜잭션 %d가 시작했습니다(offset %d)."
                            .formatted(open.txId, boundary.txId(), offset));
        }
        // 같은 번호의 BEGIN이 다시 오면 커넥터가 처음부터 다시 보낸 것이다. 모으던 것을 버린다.
        open = new Open(boundary.txId(), offset);
    }

    private void add(ChangeEvent event, long offset) {
        if (open == null) {
            throw new CdcProtocolException(
                    "트랜잭션 경계 밖의 변경입니다(트랜잭션 %d, offset %d).".formatted(event.txId(), offset));
        }
        if (event.txId() != open.txId) {
            throw new CdcProtocolException(
                    "트랜잭션 %d 안에 다른 트랜잭션 %d의 변경이 왔습니다(offset %d)."
                            .formatted(open.txId, event.txId(), offset));
        }
        if (open.events.size() >= maxEvents) {
            throw new TooLargeTransactionException(open.txId, maxEvents);
        }
        open.events.add(event);
        open.countsByTable.merge(event.resourceKey(), 1, Integer::sum);
    }

    private SourceTransaction end(TransactionBoundary boundary, long offset) {
        if (open == null) {
            throw new CdcProtocolException(
                    "BEGIN 없이 트랜잭션 %d의 END가 왔습니다(offset %d).".formatted(boundary.txId(), offset));
        }
        if (boundary.txId() != open.txId) {
            throw new CdcProtocolException(
                    "트랜잭션 %d를 모으는 중에 트랜잭션 %d의 END가 왔습니다(offset %d)."
                            .formatted(open.txId, boundary.txId(), offset));
        }
        if (boundary.eventCount() != open.events.size()
                || !boundary.eventCountsByTable().equals(open.countsByTable)) {
            throw new CdcProtocolException(
                    "트랜잭션 %d의 변경 수가 END와 다릅니다(END %d, 받은 %d)."
                            .formatted(open.txId, boundary.eventCount(), open.events.size()));
        }
        SourceTransaction done =
                new SourceTransaction(
                        open.txId, List.copyOf(open.events), open.beginOffset, offset);
        open = null;
        return done;
    }

    private static final class Open {

        final long txId;
        final long beginOffset;
        final List<ChangeEvent> events = new ArrayList<>();
        final Map<String, Integer> countsByTable = new HashMap<>();

        Open(long txId, long beginOffset) {
            this.txId = txId;
            this.beginOffset = beginOffset;
        }
    }

    /** 원천 트랜잭션 하나의 변경이 너무 많다. 부분 반영하지 않고 복구(다시 읽기)로 간다. */
    public static class TooLargeTransactionException extends RuntimeException {

        private final long txId;

        public TooLargeTransactionException(long txId, int maxEvents) {
            super("원천 트랜잭션 %d의 변경이 %d건을 넘었습니다.".formatted(txId, maxEvents));
            this.txId = txId;
        }

        public long txId() {
            return txId;
        }
    }
}
