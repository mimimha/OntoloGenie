package com.axcore.platform.source.cdc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CDC 소비 루프 설정(198-6, {@code application-batch.yml}의 {@code axcore.cdc.consumer}).
 *
 * @param enabled 소비자를 띄울지. 배포 설정으로 켠다
 * @param bootstrapServers Kafka 주소
 * @param pollTimeout 한 번 가져올 때 기다리는 시간
 * @param maxEvents 원천 트랜잭션 하나의 변경 수 상한. 넘으면 그 원천을 복구(TRANSACTION_TOO_LARGE)로 돌린다
 * @param maxLag 받은 메시지 시각보다 이만큼 늦게 처리하면 수집 비정상
 * @param staleAfter 메시지(heartbeat 포함)가 이만큼 없으면 수집 비정상
 * @param retryDelay 처리 장애 뒤 같은 위치부터 다시 시도하기까지 기다리는 시간
 * @param maxApplyFailures 같은 위치에서 반영이 이만큼 연속으로 실패하면 그 원천을 복구(APPLY_FAILED)로 돌린다(364)
 * @param refreshInterval 소비할 원천을 다시 찾는 간격, 복구 중인 원천이 풀렸는지 확인하는 간격
 */
@ConfigurationProperties("axcore.cdc.consumer")
public record CdcConsumerProperties(
        boolean enabled,
        String bootstrapServers,
        Duration pollTimeout,
        int maxEvents,
        Duration maxLag,
        Duration staleAfter,
        Duration retryDelay,
        int maxApplyFailures,
        Duration refreshInterval) {}
