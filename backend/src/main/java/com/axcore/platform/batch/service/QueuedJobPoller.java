package com.axcore.platform.batch.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 작업 표를 주기적으로 확인한다. 등록된 {@link QueuedJobHandler}마다 QUEUED 작업을 최대 {@value
 * #MAX_JOBS_PER_POLL}건씩 처리한다. 한 종류가 몰려도 다른 종류가 오래 밀리지 않게 하려는 상한이다.
 *
 * <p>전체 읽기(SYNC_RUN)는 오래 걸리므로 다른 작업과 따로 돈다(S15P31C106-218). {@link #pollSyncRuns}가 한 주기에 1건만 처리하고,
 * 스케줄러 스레드 풀(spring.task.scheduling.pool.size)이 두 주기를 나눠 맡는다. 전체 읽기가 도는 동안에도 연결 검사·정합성 검사가 처리된다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class QueuedJobPoller {

    static final int MAX_JOBS_PER_POLL = 20;

    private final ObjectProvider<QueuedJobHandler<?>> handlers;
    private final QueuedJobRunner queuedJobRunner;

    @Scheduled(fixedDelayString = "${axcore.batch.poll-delay}")
    public void poll() {
        handlers.orderedStream()
                .filter(handler -> handler.type() != QueuedJobType.SYNC_RUN)
                .forEach(handler -> drain(handler, MAX_JOBS_PER_POLL));
    }

    @Scheduled(fixedDelayString = "${axcore.batch.poll-delay}")
    public void pollSyncRuns() {
        handlers.orderedStream()
                .filter(handler -> handler.type() == QueuedJobType.SYNC_RUN)
                .forEach(handler -> drain(handler, 1));
    }

    private void drain(QueuedJobHandler<?> handler, int maxJobs) {
        try {
            int processed = 0;
            while (processed < maxJobs && queuedJobRunner.runNext(handler)) {
                processed++;
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("작업 표 확인 실패 type={}", handler.type(), e);
        }
    }
}
