package com.axcore.platform.mapping.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.mapping.dto.MappingPreviewRequest.Draft;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.LoadFailure;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository.Preview;
import com.axcore.platform.source.repository.SourceResourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import com.axcore.platform.source.service.SourceCredentialException;
import com.axcore.platform.source.service.SourceCredentialService;
import com.axcore.platform.source.service.SourceReadFailure;
import com.axcore.platform.source.service.SourceRowConverter;
import com.axcore.platform.source.service.SourceRowConverter.Conversion;
import com.axcore.platform.source.service.SourceRowConverter.ValueFailure;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매핑 미리보기 작업(source_preview의 MAPPING_PREVIEW, API 040·102)을 배치에서 실행한다. 원천 표본(SAMPLE, 016)은 가져가지
 * 않는다(따로 처리기를 둔다).
 *
 * <ol>
 *   <li>run(트랜잭션 밖): 초안을 지금 정의와 다시 맞춰 보고(요청 뒤 바뀌었을 수 있다), SYNC 계정으로 그 표에서 앞 행을 최대 rowLimit개 읽어 전체
 *       읽기와 같은 변환기({@link SourceRowConverter})로 객체 모양을 만든다.
 *   <li>complete(한 트랜잭션): 결과({@code objects[]}·{@code rowErrors[]})를 작업 행에 쓰고 SUCCEEDED로 바꾼다.
 *       객체·연결·매핑은 저장하지 않는다.
 * </ol>
 *
 * <p>키 값이 비어 객체가 되지 않은 행과 속성 값을 바꾸지 못한 칸은 행별 오류로 남긴다. 원천 값이 들어 있으므로 결과는 요청 1시간 뒤 지운다({@link
 * SourcePreviewCleanupJob}).
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class MappingPreviewJob implements QueuedJobHandler<MappingPreviewJob.Outcome> {

    private final SourcePreviewRepository previewRepository;
    private final MappingPreviewPlanner planner;
    private final SourceResourceRepository resourceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final SourceCredentialService credentialService;
    private final SourceTableReader tableReader;
    private final SourceRowConverter converter;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public QueuedJobType type() {
        return QueuedJobType.SOURCE_PREVIEW;
    }

    @Override
    public String claimCondition() {
        return "preview_kind = 'MAPPING_PREVIEW'";
    }

    @Override
    public Outcome run(long jobId) {
        Preview preview =
                previewRepository
                        .findById(jobId)
                        .orElseThrow(() -> new IllegalStateException("미리보기 작업이 없습니다: " + jobId));
        Draft draft = draft(preview.request());
        MappingPlan plan;
        try {
            plan = planner.plan(preview.sourceResourceId(), draft);
        } catch (BusinessException e) {
            throw new QueuedJobFailure(
                    "요청한 뒤 정의나 원천 카탈로그가 바뀌어 이 초안으로 미리볼 수 없습니다. " + e.getMessage());
        }
        SourceResource resource =
                resourceRepository
                        .findById(preview.sourceResourceId())
                        .orElseThrow(() -> new IllegalStateException("원천 테이블이 없습니다."));
        DataSource source =
                dataSourceRepository
                        .findById(resource.getDataSourceId())
                        .orElseThrow(() -> new IllegalStateException("원천이 없습니다."));
        if (source.getSourceKind() != SourceKind.POSTGRES) {
            throw new QueuedJobFailure(
                    "미리보기는 PostgreSQL 원천만 지원합니다. 이 원천의 종류는 %s입니다."
                            .formatted(source.getSourceKind()));
        }
        SourceEndpoint endpoint;
        try {
            endpoint = SourceEndpoint.from(source.getConnectionConfig());
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure(e.getMessage());
        }
        SourceAccount account = syncAccount(source.getId());

        Set<String> columns = new LinkedHashSet<>(plan.keyColumns());
        plan.properties().forEach(p -> columns.add(p.columnName()));
        List<SourceRow> rows;
        try {
            rows =
                    tableReader.sample(
                            endpoint,
                            account,
                            new Table(
                                    resource.getId(),
                                    resource.getResourceKey(),
                                    List.copyOf(columns),
                                    Set.copyOf(plan.keyColumns())),
                            preview.rowLimit());
        } catch (SQLException e) {
            log.warn(
                    "매핑 미리보기 원천 읽기 실패 previewId={} resourceId={} sqlState={}",
                    jobId,
                    resource.getId(),
                    e.getSQLState());
            throw new QueuedJobFailure(SourceReadFailure.message(e));
        }

        List<Map<String, Object>> objects = new ArrayList<>();
        List<Map<String, Object>> rowErrors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            SourceRow row = rows.get(i);
            Conversion conversion = converter.convert(resource.getId(), plan, row);
            if (conversion.object() == null) {
                LoadFailure failure = conversion.failures().getFirst();
                rowErrors.add(rowError(i, failure.columnName(), null, "키 값이 비어 있어 객체를 만들 수 없습니다."));
                continue;
            }
            objects.add(object(conversion.object()));
            for (LoadFailure failure : conversion.failures()) {
                PropertyPlan property = property(plan, failure.propertyDefinitionId());
                ValueFailure reason =
                        converter.explainValueFailure(
                                row.values().get(property.columnName()),
                                property.steps(),
                                property.dataType());
                rowErrors.add(
                        rowError(i, property.columnName(), reason.transform(), reason.message()));
            }
        }
        return new Outcome(objects, rowErrors);
    }

    @Override
    public void complete(long jobId, Outcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objects", outcome.objects());
        result.put("rowErrors", outcome.rowErrors());
        if (!previewRepository.finish(jobId, json.writeValueAsString(result), Instant.now(clock))) {
            throw new QueuedJobFailure("미리보기 작업이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
    }

    @Override
    public Map<String, Object> auditResult(Outcome outcome) {
        return Map.of("objects", outcome.objects().size(), "rowErrors", outcome.rowErrors().size());
    }

    @SuppressWarnings("unchecked")
    private Draft draft(String requestJson) {
        Map<String, Object> request = json.readValue(requestJson, Map.class);
        return json.convertValue(request.get("mapping"), Draft.class);
    }

    private SourceAccount syncAccount(long dataSourceId) {
        try {
            return credentialService
                    .findAccount(dataSourceId, CredentialPurpose.SYNC)
                    .orElseThrow(() -> new QueuedJobFailure("SYNC 계정이 등록되지 않아 원천을 읽을 수 없습니다."));
        } catch (SourceCredentialException e) {
            throw new QueuedJobFailure("원천 접속 정보를 풀지 못했습니다. 서버의 암호화 키 설정을 확인해 주세요.", e);
        }
    }

    private static PropertyPlan property(MappingPlan plan, Long propertyDefinitionId) {
        return plan.properties().stream()
                .filter(p -> p.propertyDefinitionId() == propertyDefinitionId)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> object(SyncedObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceKey", object.sourceKey());
        result.put("displayName", object.displayName());
        result.put("properties", object.properties());
        return result;
    }

    private static Map<String, Object> rowError(
            int rowIndex, String column, String transform, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("rowIndex", rowIndex);
        error.put("column", column);
        error.put("transform", transform);
        error.put("message", message);
        return error;
    }

    /**
     * @param objects 변환된 객체(sourceKey·displayName·properties). DB에 저장하지 않는다
     * @param rowErrors 행별 변환 오류(rowIndex·column·transform·message)
     */
    public record Outcome(List<Map<String, Object>> objects, List<Map<String, Object>> rowErrors) {}
}
