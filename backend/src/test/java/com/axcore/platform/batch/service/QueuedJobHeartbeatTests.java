package com.axcore.platform.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.domain.QueuedJobType;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 전체 읽기(sync_run)가 도는 동안 실행 틀이 heartbeat_at을 올리고, 끝나면 멈추는지 확인한다(S15P31C106-218). */
@SpringBootTest(
        properties = {
            "spring.main.keep-alive=false",
            "axcore.batch.timeout.heartbeat=200ms",
            "axcore.batch.timeout.sync-run=1m"
        })
@ActiveProfiles("batch")
@Import({TestcontainersConfiguration.class, QueuedJobHeartbeatTests.HandlerConfig.class})
class QueuedJobHeartbeatTests {

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean StaleJobCleanupJob staleJobCleanupJob;

    @Autowired QueuedJobRunner runner;
    @Autowired SlowSyncHandler handler;
    @Autowired JdbcClient jdbc;

    private long runId;

    @BeforeEach
    void queue() {
        // runNext는 가장 먼저 들어온 수집을 가져간다. 앞 시험이 남긴 대기·실행 중 수집을 지운다.
        jdbc.sql("delete from sync_run where status in ('QUEUED', 'RUNNING')").update();
        long sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, 'heartbeat 시험 원천', 'POSTGRES') returning id
                                """)
                        .param(
                                "code",
                                "HB_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
        runId =
                jdbc.sql(
                                "insert into sync_run (data_source_id, trigger_kind) values (:s, 'SCHEDULED') returning id")
                        .param("s", sourceId)
                        .query(Long.class)
                        .single();
    }

    @Test
    void beatsWhileRunningAndStopsAfterCompletion() throws Exception {
        assertThat(runner.runNext(handler)).isTrue();

        assertThat(handler.seenDuringRun.get()).as("run이 도는 동안 heartbeat_at이 올라갔다").isNotNull();
        assertThat(status()).isEqualTo("SUCCEEDED");
        OffsetDateTime afterRun = heartbeat();
        Thread.sleep(600);
        assertThat(heartbeat()).as("끝난 뒤에는 올리지 않는다").isEqualTo(afterRun);
    }

    private String status() {
        return jdbc.sql("select status from sync_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private OffsetDateTime heartbeat() {
        return jdbc.sql("select heartbeat_at from sync_run where id = :id")
                .param("id", runId)
                .query(OffsetDateTime.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfig {

        @Bean
        SlowSyncHandler slowSyncHandler(JdbcClient jdbc) {
            return new SlowSyncHandler(jdbc);
        }
    }

    /** heartbeat 간격(200ms)보다 오래 읽는 전체 읽기를 흉내 낸다. 읽는 중에 본 heartbeat_at을 남긴다. */
    static class SlowSyncHandler implements QueuedJobHandler<Void> {

        private final JdbcClient jdbc;
        final AtomicReference<OffsetDateTime> seenDuringRun = new AtomicReference<>();

        SlowSyncHandler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public QueuedJobType type() {
            return QueuedJobType.SYNC_RUN;
        }

        @Override
        public Void run(long jobId) {
            try {
                Thread.sleep(900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            seenDuringRun.set(
                    jdbc.sql("select heartbeat_at from sync_run where id = :id")
                            .param("id", jobId)
                            .query(OffsetDateTime.class)
                            .optional()
                            .orElse(null));
            return null;
        }

        @Override
        public void complete(long jobId, Void result) {
            jdbc.sql("update sync_run set status = 'SUCCEEDED', finished_at = now() where id = :id")
                    .param("id", jobId)
                    .update();
        }
    }
}
