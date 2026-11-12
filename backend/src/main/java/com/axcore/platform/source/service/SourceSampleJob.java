package com.axcore.platform.source.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** API 016의 SAMPLE 작업만 원천에서 읽고 API 102가 조회할 결과를 저장한다. */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class SourceSampleJob implements QueuedJobHandler<SourceSampleJob.Result> {

    // 현재 ERP·MES 데이터셋의 PERSONAL_COLUMNS (ai/app/dataset/schema.py).
    private static final Map<String, String> EXCLUDED_COLUMNS =
            Map.of(
                    "erp.employee", "emp_name",
                    "erp.vendor", "contact_name",
                    "mes.worker", "name",
                    "mes.maintenance_order", "technician");

    private final SourcePreviewRepository previews;
    private final SourceResourceRepository resources;
    private final DataSourceRepository sources;
    private final SourceColumnRepository columns;
    private final SourceCredentialService credentials;
    private final SourceTableReader reader;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public QueuedJobType type() {
        return QueuedJobType.SOURCE_PREVIEW;
    }

    @Override
    public String claimCondition() {
        return "preview_kind = 'SAMPLE'";
    }

    @Override
    public Result run(long jobId) {
        var preview =
                previews.findById(jobId)
                        .filter(row -> row.expiresAt().isAfter(Instant.now(clock)))
                        .orElseThrow(() -> new QueuedJobFailure("표본 작업이 없거나 만료되었습니다."));
        var resource =
                resources
                        .findById(preview.sourceResourceId())
                        .filter(SourceSampleRequestService::isReadableTable)
                        .orElseThrow(() -> new QueuedJobFailure("읽을 수 있는 원천 테이블을 찾을 수 없습니다."));
        var source =
                sources.findById(resource.getDataSourceId())
                        .filter(row -> row.getSourceKind() == SourceKind.POSTGRES)
                        .orElseThrow(() -> new QueuedJobFailure("PostgreSQL 원천을 찾을 수 없습니다."));

        String resourceKey = resource.getResourceKey();
        String excluded = EXCLUDED_COLUMNS.get(resourceKey);
        List<String> selected =
                columns
                        .findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(resource.getId())
                        .stream()
                        .filter(SourceColumn::isPresent)
                        .map(SourceColumn::getColumnName)
                        .filter(name -> !name.equals(excluded))
                        .toList();
        if (selected.isEmpty()) {
            throw new QueuedJobFailure("표본으로 조회할 수 있는 컬럼이 없습니다.");
        }

        SourceEndpoint endpoint;
        try {
            endpoint = SourceEndpoint.from(source.getConnectionConfig());
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure("원천 접속 설정을 확인해 주세요.");
        }
        SourceAccount account = syncAccount(resource.getDataSourceId());
        var table = new Table(resource.getId(), resourceKey, selected, Set.of());
        try {
            var rows =
                    reader.sample(endpoint, account, table, preview.rowLimit()).stream()
                            .map(row -> selected.stream().map(row.values()::get).toList())
                            .toList();
            return new Result(selected, rows);
        } catch (SQLException e) {
            log.warn("원천 표본 읽기 실패 previewId={} sqlState={}", jobId, e.getSQLState());
            throw new QueuedJobFailure(SourceReadFailure.message(e));
        } catch (RuntimeException e) {
            log.warn("원천 표본 변환 실패 previewId={} exception={}", jobId, e.getClass().getSimpleName());
            throw new QueuedJobFailure("원천 표본 값을 처리하지 못했습니다.");
        }
    }

    @Override
    public void complete(long jobId, Result result) {
        String payload;
        try {
            payload = json.writeValueAsString(result);
        } catch (RuntimeException e) {
            log.warn(
                    "원천 표본 결과 변환 실패 previewId={} exception={}",
                    jobId,
                    e.getClass().getSimpleName());
            throw new QueuedJobFailure("원천 표본 결과를 처리하지 못했습니다.");
        }
        try {
            if (!previews.finish(jobId, payload, Instant.now(clock))) {
                throw new QueuedJobFailure("표본 작업이 이미 종료되었거나 정리되었습니다.");
            }
        } catch (DataAccessException e) {
            log.warn(
                    "원천 표본 결과 저장 실패 previewId={} exception={}",
                    jobId,
                    e.getClass().getSimpleName());
            throw new QueuedJobFailure("원천 표본 결과를 저장하지 못했습니다.");
        }
    }

    @Override
    public Map<String, Object> auditResult(Result result) {
        return Map.of("rowCount", result.rows().size());
    }

    private SourceAccount syncAccount(long sourceId) {
        try {
            return credentials
                    .findAccount(sourceId, CredentialPurpose.SYNC)
                    .orElseThrow(() -> new QueuedJobFailure("원천 SYNC 계정이 등록되지 않았습니다."));
        } catch (SourceCredentialException e) {
            throw new QueuedJobFailure("원천 SYNC 계정의 접속 정보를 읽을 수 없습니다.");
        }
    }

    public record Result(List<String> columns, List<List<Object>> rows) {}
}
