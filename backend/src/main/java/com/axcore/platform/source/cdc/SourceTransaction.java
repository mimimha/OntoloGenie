package com.axcore.platform.source.cdc;

import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import java.util.List;

/**
 * 완성된 원천 트랜잭션 하나(BEGIN~END, Jira 198-2). 소비자는 이것을 플랫폼 트랜잭션 하나로 반영하고, 같은 트랜잭션에서 소비 위치를 {@link
 * #nextOffset()}으로 저장한다.
 *
 * @param txId 원천 트랜잭션 번호(32비트)
 * @param events 원천 커밋 순서의 변경(0건일 수도 있다)
 * @param beginOffset BEGIN의 Kafka offset
 * @param endOffset END의 Kafka offset
 */
public record SourceTransaction(
        long txId, List<ChangeEvent> events, long beginOffset, long endOffset) {

    public SourceTransaction {
        events = List.copyOf(events);
    }

    /** 이 트랜잭션을 반영한 뒤 다음에 읽을 offset. */
    public long nextOffset() {
        return endOffset + 1;
    }

    /** 원천 커밋 시각(원천 서버 시계). 변경이 없으면 -1. */
    public long commitTimeMs() {
        return events.stream().mapToLong(ChangeEvent::commitTimeMs).max().orElse(-1);
    }
}
