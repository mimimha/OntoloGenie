package com.axcore.platform.source.cdc;

/**
 * Debezium 이벤트의 원천 트랜잭션 번호({@code source.txId}, 32비트)를 PostgreSQL {@code xid8}(64비트, epoch 포함)로
 * 복원한다(Jira 198-3, 결정 문서 3.3·10.2, 시험 5).
 *
 * <p>객체의 적재 스냅샷과 비교({@code pg_visible_in_snapshot})하려면 64비트 번호가 필요하다. 32비트를 그대로 쓰면 번호가 한 바퀴(약 42억)
 * 넘어간 뒤 비교가 무너진다(시험 5: 4만 건 중 정답 0건, 가시성 판단 84만 쌍 중 42만 쌍 틀림).
 *
 * <p>규칙: 기준값의 epoch를 붙인 뒤, 기준값과 차이가 2³¹을 넘으면 epoch를 하나 내리거나 올린다(가장 가까운 epoch). 기준값은
 *
 * <ul>
 *   <li>소비자가 마지막으로 반영한 원천 트랜잭션의 복원된 번호다. 소비 위치({@code cdc_offset})와 같은 플랫폼 트랜잭션에 저장한다(198-4·5).
 *   <li>처음 시작할 때만 원천의 {@code pg_current_snapshot()} xmax를 쓴다(번호를 새로 발급하지 않는 함수).
 *   <li>객체에 저장된 옛 적재 스냅샷은 쓰지 않는다. 오래되면 2³¹을 넘어 틀린다.
 * </ul>
 *
 * <p>가장 가까운 epoch 규칙은 기준값과 실제 번호의 거리가 2³¹보다 작을 때만 맞다. 32비트만으로는 여러 바퀴 차이를 알 수 없으므로, 복원한 번호와 기준값의 거리가
 * {@code maxDistance}를 넘으면 추정하지 않고 {@link UncertainTransactionIdException}을 던진다. 소비자는 그 원천을 복구 상태로
 * 돌린다(설계 검토 R6). 기준값보다 작은 번호도 정상이다. 오래 걸린 트랜잭션은 번호를 먼저 받고 나중에 커밋한다.
 */
public final class TransactionIdRestorer {

    /** 32비트 번호의 범위(2³²). */
    static final long WRAP = 1L << 32;

    private static final long HALF = 1L << 31;

    /** 기본 허용 거리 2³⁰(약 10억). 가장 가까운 epoch 규칙이 틀리는 2³¹보다 넉넉히 작게 둔다. */
    public static final long DEFAULT_MAX_DISTANCE = 1L << 30;

    private final long maxDistance;

    public TransactionIdRestorer() {
        this(DEFAULT_MAX_DISTANCE);
    }

    public TransactionIdRestorer(long maxDistance) {
        if (maxDistance < 1 || maxDistance >= HALF) {
            throw new IllegalArgumentException("maxDistance는 1 이상 2^31 미만입니다.");
        }
        this.maxDistance = maxDistance;
    }

    /**
     * @param txId Debezium {@code source.txId}(0 이상 2³² 미만)
     * @param reference 기준값(복원된 64비트 번호, 3 이상)
     * @return 복원한 {@code xid8} 값
     * @throws UncertainTransactionIdException 복원한 번호가 기준값에서 {@code maxDistance}보다 멀다
     */
    public long restore(long txId, long reference) {
        if (txId < 0 || txId >= WRAP) {
            throw new IllegalArgumentException("txId는 32비트 범위(0 이상 2^32 미만)입니다.");
        }
        if (reference < 3) {
            throw new IllegalArgumentException("기준값은 3 이상의 xid8입니다.");
        }
        long epoch = reference >>> 32;
        long candidate = (epoch << 32) | txId;
        if (candidate - reference > HALF && epoch > 0) {
            candidate -= WRAP;
        } else if (reference - candidate > HALF) {
            candidate += WRAP;
        }
        if (Math.abs(candidate - reference) > maxDistance) {
            throw new UncertainTransactionIdException(txId, reference);
        }
        return candidate;
    }

    /** 32비트 번호만으로는 epoch를 확신할 수 없다. 값 대신 번호만 담는다. */
    public static class UncertainTransactionIdException extends RuntimeException {

        public UncertainTransactionIdException(long txId, long reference) {
            super(
                    "원천 트랜잭션 번호 %d를 기준값 %d에서 확실히 복원할 수 없습니다. 그 원천을 복구(다시 읽기)로 돌린다."
                            .formatted(txId, reference));
        }
    }
}
