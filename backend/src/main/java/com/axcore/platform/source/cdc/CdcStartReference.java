package com.axcore.platform.source.cdc;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.service.SourceCredentialService;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 원천을 처음 소비할 때 쓸 txId epoch 복원 기준값(198-3·198-6). 원천의 {@code pg_current_snapshot()} xmax를 SYNC 계정으로
 * 읽는다. 번호를 새로 발급하지 않는 읽기 전용 조회라 원천에 아무것도 쓰지 않는다. 이후 기준값은 {@code cdc_offset.last_txid}다.
 */
@Component
@RequiredArgsConstructor
public class CdcStartReference {

    private final DataSourceRepository dataSourceRepository;
    private final SourceCredentialService credentialService;
    private final SourceTableReader tableReader;

    /**
     * @throws IllegalStateException 원천이 없거나 SYNC 계정이 없음, 원천에 접속하지 못함
     */
    public long read(long dataSourceId) {
        DataSource source =
                dataSourceRepository
                        .findById(dataSourceId)
                        .orElseThrow(() -> new IllegalStateException("원천이 없습니다: " + dataSourceId));
        SourceAccount account =
                credentialService
                        .findAccount(dataSourceId, CredentialPurpose.SYNC)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "SYNC 계정이 없어 epoch 기준값을 읽을 수 없습니다: "
                                                        + dataSourceId));
        try {
            return tableReader.currentXmax(
                    SourceEndpoint.from(source.getConnectionConfig()), account);
        } catch (SQLException e) {
            // 드라이버 메시지에는 주소·계정명이 들어갈 수 있어 SQLSTATE만 남긴다
            throw new IllegalStateException(
                    "원천 epoch 기준값을 읽지 못했습니다(SQLSTATE %s)".formatted(e.getSQLState()));
        }
    }
}
