package com.axcore.platform.source.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.ontology.domain.FullLoad;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.ontology.service.ObjectSyncService;
import com.axcore.platform.ontology.service.ObjectSyncService.FinishResult;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.FullLoadPlan;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SyncStrategy;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Row;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import com.axcore.platform.source.repository.SourceTableReader.TooManyRowsException;
import com.axcore.platform.source.repository.SyncRunRepository;
import com.axcore.platform.source.repository.SyncRunRepository.Counts;
import com.axcore.platform.source.repository.SyncRunRepository.Target;
import com.axcore.platform.source.service.SourceRowConverter.Conversion;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 원천 테이블 전체 읽기(sync_run의 FULL 테이블, Jira 197)를 배치에서 실행한다. 첫 적재, 매핑 변경 뒤 다시 읽기, 복구(RECOVERY),
 * FULL_COMPARE 원천의 수집이 모두 이 작업이다. 실행을 만드는 쪽(API 073, 매핑 변경)은 sync_run을 QUEUED로, 테이블마다
 * sync_run_resource를 mode FULL·QUEUED로 넣는다.
 *
 * <ol>
 *   <li>run(트랜잭션 밖): 켜진 매핑으로 계획을 만들고, SYNC 계정으로 원천의 모든 테이블을 스냅샷 하나로 읽어 객체 값으로 바꾼다.
 *   <li>complete(한 트랜잭션): 원천 테이블 행을 잠그고 계획이 그대로인지 다시 본 뒤 객체를 쓴다({@link ObjectSyncService}). 이번에 안
 *       나온 객체는 삭제 표시하고, CDC 테이블은 끝난 세대와 읽기 시점 LSN을 올린다.
 * </ol>
 *
 * <p>세대는 sync_run.id다. 쓰기 조건(스냅샷 가시성·세대·삭제 흔적)은 decisions/2026-11-05-cdc-initial-load.md와 V11을 따른다.
 * 행을 메모리에 모두 올리므로 한 실행에서 읽는 행 수를 {@code axcore.source-load.max-rows}로 제한한다. 큰 테이블을 나눠 읽는 일은 아직 하지
 * 않았다.
 */
@Slf4j
@Component
@Profile("batch")
public class SyncRunJob implements QueuedJobHandler<SyncRunJob.Outcome> {

    private final SyncRunRepository syncRunRepository;
    private final FullLoadPlanner planner;
    private final SourceTableReader tableReader;
    private final SourceRowConverter converter;
    private final SourceCredentialService credentialService;
    private final ObjectSyncService objectSyncService;
    private final Clock clock;
    private final int maxRows;

    public SyncRunJob(
            SyncRunRepository syncRunRepository,
            FullLoadPlanner planner,
            SourceTableReader tableReader,
            SourceRowConverter converter,
            SourceCredentialService credentialService,
            ObjectSyncService objectSyncService,
            Clock clock,
            @Value("${axcore.source-load.max-rows}") int maxRows) {
        this.syncRunRepository = syncRunRepository;
        this.planner = planner;
        this.tableReader = tableReader;
        this.converter = converter;
        this.credentialService = credentialService;
        this.objectSyncService = objectSyncService;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    @Override
    public QueuedJobType type() {
        return QueuedJobType.SYNC_RUN;
    }

    @Override
    public Outcome run(long jobId) {
        Target target =
                syncRunRepository
                        .findTarget(jobId)
                        .orElseThrow(() -> new IllegalStateException("수집 실행이 없습니다: " + jobId));
        List<FullLoadPlan> plans =
                syncRunRepository.findQueuedFullResources(jobId).stream()
                        .map(planner::plan)
                        .toList();
        List<FullLoadPlan> toRead =
                plans.stream().filter(plan -> !plan.mappings().isEmpty()).toList();
        if (toRead.isEmpty()) {
            return new Outcome(null, 0L, plans.stream().map(ResourceLoad::empty).toList());
        }
        if (target.sourceKind() != SourceKind.POSTGRES) {
            throw new QueuedJobFailure(
                    "전체 읽기는 PostgreSQL 원천만 지원합니다. 이 원천의 종류는 %s입니다.".formatted(target.sourceKind()));
        }
        SourceEndpoint endpoint;
        try {
            endpoint = SourceEndpoint.from(target.connectionConfig());
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure(e.getMessage());
        }
        SourceAccount account = syncAccount(target.dataSourceId());

        SourceTableReader.Result read;
        try {
            read =
                    tableReader.read(
                            endpoint,
                            account,
                            toRead.stream()
                                    .map(
                                            plan ->
                                                    new Table(
                                                            plan.resourceId(),
                                                            plan.resourceKey(),
                                                            plan.columns(),
                                                            plan.keyColumns()))
                                    .toList(),
                            maxRows);
        } catch (TooManyRowsException e) {
            throw new QueuedJobFailure(
                    "원천 행이 한 번에 읽을 수 있는 수(%d)를 넘었습니다. 큰 테이블을 나눠 읽는 기능은 아직 없습니다."
                            .formatted(maxRows));
        } catch (SQLException e) {
            log.warn(
                    "원천 전체 읽기 실패 syncRunId={} dataSourceId={} sqlState={}",
                    jobId,
                    target.dataSourceId(),
                    e.getSQLState());
            throw new QueuedJobFailure(failureMessage(e));
        }

        FullLoad load = new FullLoad(read.snapshot(), jobId);
        List<ResourceLoad> loads = new ArrayList<>();
        for (FullLoadPlan plan : plans) {
            if (plan.mappings().isEmpty()) {
                loads.add(ResourceLoad.empty(plan));
                continue;
            }
            List<Row> rows = read.rows().get(plan.resourceId());
            List<SyncedObject> objects = new ArrayList<>();
            int skippedRows = 0;
            int failedValues = 0;
            int duplicateKeyRows = 0;
            for (MappingPlan mapping : plan.mappings()) {
                // 한 매핑 안에서 같은 원천 키는 한 객체다. 뒤 행이 앞 행을 조용히 덮지 않게 첫 행만 쓰고 나머지는 센다.
                Map<Map<String, Object>, SyncedObject> byKey = new LinkedHashMap<>();
                for (Row row : rows) {
                    Conversion conversion = converter.convert(plan.resourceId(), mapping, row);
                    failedValues += conversion.failedValues();
                    if (conversion.object() == null) {
                        skippedRows++;
                    } else if (byKey.putIfAbsent(
                                    conversion.object().sourceKey(), conversion.object())
                            != null) {
                        duplicateKeyRows++;
                    }
                }
                objects.addAll(byKey.values());
            }
            if (duplicateKeyRows > 0) {
                log.warn(
                        "전체 읽기에서 같은 원천 키 행을 건너뜀 syncRunId={} resourceId={} rows={}",
                        jobId,
                        plan.resourceId(),
                        duplicateKeyRows);
            }
            loads.add(
                    new ResourceLoad(
                            plan,
                            rows.size(),
                            objects,
                            skippedRows,
                            failedValues,
                            duplicateKeyRows));
        }
        return new Outcome(load, read.lsn(), loads);
    }

    @Override
    public void complete(long jobId, Outcome outcome) {
        if (!syncRunRepository.lockIfRunning(jobId)) {
            throw new QueuedJobFailure("수집 실행이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
        syncRunRepository.lockResources(
                outcome.resources().stream().map(r -> r.plan().resourceId()).toList());
        for (ResourceLoad resource : outcome.resources()) {
            if (!planner.plan(resource.plan().resourceId()).equals(resource.plan())) {
                throw new QueuedJobFailure(
                        "수집하는 동안 원천 테이블 %s의 매핑이 바뀌었습니다. 다시 수집해 주세요."
                                .formatted(resource.plan().resourceKey()));
            }
        }
        Instant now = Instant.now(clock);
        for (ResourceLoad resource : outcome.resources()) {
            FullLoadPlan plan = resource.plan();
            Counts counts = new Counts(resource.rowsRead(), 0, 0);
            Long toLsn = null;
            if (!plan.mappings().isEmpty()) {
                counts = write(plan, resource, outcome.load());
                if (plan.strategy() == SyncStrategy.CDC) {
                    syncRunRepository.markLoaded(plan.resourceId(), jobId, outcome.lsn());
                    toLsn = outcome.lsn();
                }
            }
            syncRunRepository.finishResource(jobId, plan.resourceId(), counts, toLsn, now);
        }
        syncRunRepository.finish(jobId, now);
    }

    private Counts write(FullLoadPlan plan, ResourceLoad resource, FullLoad load) {
        int upserted = 0;
        for (SyncedObject object : resource.objects()) {
            if (objectSyncService.applyLoaded(object, load).isPresent()) {
                upserted++;
            }
        }
        int deleted = 0;
        for (MappingPlan mapping : plan.mappings()) {
            FinishResult finish =
                    objectSyncService.finishLoad(plan.resourceId(), mapping.mappingId(), load);
            deleted += finish.objectsMarkedMissing();
        }
        return new Counts(resource.rowsRead(), upserted, deleted);
    }

    @Override
    public void failed(long jobId, String errorMessage) {
        syncRunRepository.failResources(jobId, errorMessage, Instant.now(clock));
    }

    @Override
    public Map<String, Object> auditResult(Outcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", outcome.resources().size());
        result.put("rowsRead", outcome.resources().stream().mapToInt(ResourceLoad::rowsRead).sum());
        result.put("objects", outcome.resources().stream().mapToInt(r -> r.objects().size()).sum());
        result.put(
                "skippedRows",
                outcome.resources().stream().mapToInt(ResourceLoad::skippedRows).sum());
        result.put(
                "failedValues",
                outcome.resources().stream().mapToInt(ResourceLoad::failedValues).sum());
        result.put(
                "duplicateKeyRows",
                outcome.resources().stream().mapToInt(ResourceLoad::duplicateKeyRows).sum());
        return result;
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

    // 드라이버 메시지에는 주소·계정명·값이 들어갈 수 있어 SQLSTATE로 고른 문구만 남긴다.
    private static String failureMessage(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.equals("28P01") || state.equals("28000")) {
            return "SYNC 계정으로 원천에 로그인하지 못했습니다. 계정과 비밀번호를 확인해 주세요.";
        }
        if (state.startsWith("08")) {
            return "원천에 접속하지 못했습니다. 주소와 네트워크를 확인해 주세요.";
        }
        if (state.equals("42501")) {
            return "SYNC 계정에 원천 테이블이나 컬럼을 읽을 권한이 없습니다. 연결 검사를 다시 실행해 권한을 확인해 주세요.";
        }
        if (state.equals("42P01") || state.equals("42703") || state.equals("42P09")) {
            return "원천에서 매핑한 테이블이나 컬럼을 찾지 못했습니다. 연결 검사를 다시 실행해 카탈로그를 갱신해 주세요.";
        }
        if (state.equals("57014")) {
            return "원천 테이블을 읽는 시간이 너무 오래 걸려 중단했습니다.";
        }
        return "원천 테이블을 읽지 못했습니다(SQLSTATE %s).".formatted(state.isEmpty() ? "없음" : state);
    }

    /**
     * @param load 읽기 스냅샷과 세대. 읽은 테이블이 없으면 null
     * @param lsn 읽기 시점 원천 LSN
     */
    public record Outcome(FullLoad load, long lsn, List<ResourceLoad> resources) {}

    /**
     * @param skippedRows 원천 키가 비어 객체로 만들지 않은 행 수(매핑마다 셈)
     * @param failedValues 자료형으로 바꾸지 못해 뺀 속성 값 수
     * @param duplicateKeyRows 같은 매핑에서 앞 행과 원천 키가 같아 쓰지 않은 행 수(매핑마다 셈). 키가 원천에서 유일하지 않은 뷰 등
     */
    public record ResourceLoad(
            FullLoadPlan plan,
            int rowsRead,
            List<SyncedObject> objects,
            int skippedRows,
            int failedValues,
            int duplicateKeyRows) {

        static ResourceLoad empty(FullLoadPlan plan) {
            return new ResourceLoad(plan, 0, List.of(), 0, 0, 0);
        }
    }
}
