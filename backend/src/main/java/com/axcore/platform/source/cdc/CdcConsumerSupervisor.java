package com.axcore.platform.source.cdc;

import com.axcore.platform.source.cdc.CdcTransactionApplier.CdcSource;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.CdcConsumerRepository;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CDC 표가 있는 원천마다 소비 루프({@link CdcSourceConsumer}) 하나를 전용 스레드로 띄운다(Jira 198-6, 결정 문서 11장 7). 배치
 * 프로필에서 {@code axcore.cdc.consumer.enabled=true}일 때만 돈다. {@code @Scheduled} 스레드 풀과 따로 돌아서 전체 읽기·검사
 * 작업과 서로 막지 않는다.
 *
 * <p>{@code refreshInterval}마다 원천 목록을 다시 보고, 새 원천이나 멈춘 스레드는 새로 띄운다. 원천 하나에 소비자는 하나다. 같은 원천의 반영은
 * {@code cdc_offset} 행 잠금으로도 한 번에 하나만 된다. 끌 때는 진행 중인 반영이 끝나기를 기다린다.
 */
@Slf4j
@Component
@Profile("batch")
@ConditionalOnProperty(prefix = "axcore.cdc.consumer", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CdcConsumerProperties.class)
public class CdcConsumerSupervisor implements SmartLifecycle {

    private static final long STOP_WAIT_MILLIS = 30_000;

    private final CdcConsumerProperties properties;
    private final SourceResourceRepository resourceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final CdcMessageParser parser;
    private final CdcTransactionApplier applier;
    private final CdcConsumerRepository consumerRepository;
    private final CdcStartReference startReference;
    private final Clock clock;
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private ScheduledExecutorService refresher;
    private volatile boolean started;

    public CdcConsumerSupervisor(
            CdcConsumerProperties properties,
            SourceResourceRepository resourceRepository,
            DataSourceRepository dataSourceRepository,
            CdcMessageParser parser,
            CdcTransactionApplier applier,
            CdcConsumerRepository consumerRepository,
            CdcStartReference startReference,
            Clock clock) {
        this.properties = properties;
        this.resourceRepository = resourceRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.parser = parser;
        this.applier = applier;
        this.consumerRepository = consumerRepository;
        this.startReference = startReference;
        this.clock = clock;
    }

    @Override
    public void start() {
        refresher =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "cdc-supervisor");
                            thread.setDaemon(true);
                            return thread;
                        });
        refresher.scheduleWithFixedDelay(
                this::refresh, 0, properties.refreshInterval().toMillis(), TimeUnit.MILLISECONDS);
        started = true;
        log.info("CDC 소비 루프 시작 bootstrap={}", properties.bootstrapServers());
    }

    @Override
    public void stop() {
        started = false;
        if (refresher != null) {
            refresher.shutdownNow();
        }
        running.values().forEach(r -> r.consumer().stop());
        for (Running r : running.values()) {
            try {
                r.thread().join(STOP_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        running.clear();
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    /** 지금 소비 중인 원천(시험·상태 확인용). */
    public List<Long> runningSources() {
        return running.entrySet().stream()
                .filter(e -> e.getValue().thread().isAlive())
                .map(Map.Entry::getKey)
                .toList();
    }

    private void refresh() {
        try {
            List<Long> ids = resourceRepository.findDataSourceIdsBySyncStrategy(SyncStrategy.CDC);
            for (DataSource source : dataSourceRepository.findAllById(ids)) {
                Running current = running.get(source.getId());
                if (current == null || !current.thread().isAlive()) {
                    launch(new CdcSource(source.getId(), source.getCode() + ".all"));
                }
            }
        } catch (RuntimeException e) {
            log.warn("CDC 소비할 원천을 찾지 못함 error={}", e.toString());
        }
    }

    private void launch(CdcSource source) {
        CdcSourceConsumer consumer =
                new CdcSourceConsumer(
                        source,
                        new KafkaConsumer<>(kafkaProperties(source)),
                        parser,
                        applier,
                        consumerRepository,
                        startReference,
                        properties,
                        clock);
        Thread thread = new Thread(consumer, "cdc-" + source.topic());
        thread.setDaemon(true);
        running.put(source.dataSourceId(), new Running(consumer, thread));
        thread.start();
        log.info("CDC 소비자 시작 topic={}", source.topic());
    }

    // 그룹 없이 assign으로 읽고 Kafka에 위치를 커밋하지 않는다. 위치는 cdc_offset이 기준이다.
    private Properties kafkaProperties(CdcSource source) {
        Properties kafka = new Properties();
        kafka.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        kafka.put(ConsumerConfig.CLIENT_ID_CONFIG, "axcore-cdc-" + source.topic());
        kafka.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        kafka.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        kafka.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        kafka.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return kafka;
    }

    private record Running(CdcSourceConsumer consumer, Thread thread) {}
}
