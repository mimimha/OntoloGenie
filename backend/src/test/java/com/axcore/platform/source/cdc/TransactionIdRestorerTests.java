package com.axcore.platform.source.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.source.cdc.TransactionIdRestorer.UncertainTransactionIdException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 원천 트랜잭션 번호 epoch 복원(Jira 198-3). 한 바퀴 경계는 결정 문서의 시험 5(epoch 5 → 6, 4만 건)와 같은 범위로 확인한다. */
class TransactionIdRestorerTests {

    private static final long WRAP = 1L << 32;

    private final TransactionIdRestorer restorer = new TransactionIdRestorer();

    @Test
    void restoresSequenceCrossingWraparoundWithPreviousAsReference() {
        long full = (5L << 32) + 4_294_934_528L; // 시험 5와 같은 시작점: epoch 5, 한 바퀴 3만 2천 개 전
        long previous = full;
        int crossed = 0;
        for (int i = 0; i < 40_000; i++) {
            full = next(full);
            if ((full >>> 32) == 6 && (previous >>> 32) == 5) {
                crossed++;
            }
            assertThat(restorer.restore(low32(full), previous)).isEqualTo(full);
            previous = full;
        }
        assertThat(crossed).isEqualTo(1);
        assertThat(full >>> 32).isEqualTo(6);
    }

    @Test
    void restoresWithFixedStartReference() {
        long start = (5L << 32) + 4_294_934_528L;
        long full = start;
        for (int i = 0; i < 40_000; i++) {
            full = next(full);
            assertThat(restorer.restore(low32(full), start)).isEqualTo(full);
        }
    }

    @Test
    void restoresNumbersOlderThanReference() {
        // 오래 걸린 트랜잭션은 번호를 먼저 받고 나중에 커밋한다. 기준값보다 작은 번호도 정상이다.
        assertThat(restorer.restore(900, (2L << 32) + 1_000)).isEqualTo((2L << 32) + 900);
        // 기준값은 한 바퀴 넘어간 직후, 번호는 넘어가기 전 epoch
        assertThat(restorer.restore(4_294_967_200L, (6L << 32) + 10))
                .isEqualTo((5L << 32) + 4_294_967_200L);
        // 기준값은 넘어가기 직전, 번호는 넘어간 뒤 epoch
        assertThat(restorer.restore(5, (5L << 32) + 4_294_967_290L)).isEqualTo((6L << 32) + 5);
    }

    @Test
    void refusesToGuessWhenTooFarFromReference() {
        long reference = (5L << 32) + 100;

        assertThatThrownBy(() -> restorer.restore(100 + (1L << 30) + 10, reference))
                .isInstanceOf(UncertainTransactionIdException.class);
        assertThatThrownBy(() -> restorer.restore(100 + (1L << 31), reference))
                .isInstanceOf(UncertainTransactionIdException.class);
        // epoch 0에서 이전 epoch로 내려가야 하는 번호는 있을 수 없다
        assertThatThrownBy(() -> restorer.restore(4_000_000_000L, 100))
                .isInstanceOf(UncertainTransactionIdException.class);
        // 허용 거리는 줄일 수 있다
        assertThatThrownBy(() -> new TransactionIdRestorer(1_000).restore(2_000, (1L << 32) + 10))
                .isInstanceOf(UncertainTransactionIdException.class);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> restorer.restore(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restorer.restore(WRAP, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restorer.restore(5, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionIdRestorer(1L << 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesRealPostgresTransactionIds() throws Exception {
        try (PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            postgres.start();
            try (Connection connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                long reference =
                        single(statement, "select pg_snapshot_xmax(pg_current_snapshot())::text");
                List<Long> fulls = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    fulls.add(single(statement, "select pg_current_xact_id()::text"));
                }
                for (long full : fulls) {
                    // Debezium txId는 32비트 값이다
                    long txId =
                            single(statement, "select (%d %% 4294967296)::text".formatted(full));
                    assertThat(restorer.restore(txId, reference)).isEqualTo(full);
                }
            }
        }
    }

    // PostgreSQL FullTransactionId: 32비트가 넘어가면 epoch가 하나 오르고 번호는 3부터(0·1·2는 특수 번호)
    private static long next(long full) {
        long low = low32(full) + 1;
        if (low == WRAP) {
            return (((full >>> 32) + 1) << 32) | 3;
        }
        return ((full >>> 32) << 32) | low;
    }

    private static long low32(long full) {
        return full & (WRAP - 1);
    }

    private static long single(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return Long.parseLong(rs.getString(1));
        }
    }
}
