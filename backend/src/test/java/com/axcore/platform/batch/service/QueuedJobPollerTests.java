package com.axcore.platform.batch.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axcore.platform.batch.domain.QueuedJobType;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 전체 읽기(SYNC_RUN)가 다른 작업과 다른 주기에서 한 번에 1건만 처리되는지 확인한다(S15P31C106-218). */
class QueuedJobPollerTests {

    private final QueuedJobRunner runner = mock(QueuedJobRunner.class);
    private final QueuedJobHandler<?> syncRuns = handler(QueuedJobType.SYNC_RUN);
    private final QueuedJobHandler<?> sourceChecks = handler(QueuedJobType.SOURCE_CHECK);
    private QueuedJobPoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        ObjectProvider<QueuedJobHandler<?>> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenAnswer(invocation -> Stream.of(syncRuns, sourceChecks));
        // 늘 다음 작업이 있다고 답해 상한까지 도는지 본다.
        when(runner.runNext(any())).thenReturn(true);
        poller = new QueuedJobPoller(handlers, runner);
    }

    @Test
    void pollSkipsFullLoads() {
        poller.poll();

        verify(runner, times(QueuedJobPoller.MAX_JOBS_PER_POLL)).runNext(sourceChecks);
        verify(runner, never()).runNext(syncRuns);
    }

    @Test
    void pollSyncRunsTakesOneFullLoadOnly() {
        poller.pollSyncRuns();

        verify(runner, times(1)).runNext(syncRuns);
        verify(runner, never()).runNext(sourceChecks);
    }

    @Test
    void rejectsSyncRunTimeoutShorterThanTwoHeartbeats() {
        assertThatThrownBy(
                        () ->
                                new BatchTimeoutProperties(
                                        Duration.ofMinutes(3),
                                        Duration.ofMinutes(5),
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static QueuedJobHandler<?> handler(QueuedJobType type) {
        QueuedJobHandler<?> handler = mock(QueuedJobHandler.class);
        when(handler.type()).thenReturn(type);
        return handler;
    }
}
