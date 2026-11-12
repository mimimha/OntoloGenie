package com.axcore.platform.source.service;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.audit.domain.Actor;
import com.axcore.platform.audit.domain.AuditEntry;
import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.service.ActionDefinitionLookup;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.dto.SyncScheduleRequest;
import com.axcore.platform.source.dto.SyncScheduleResponse;
import com.axcore.platform.source.repository.DataSourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원천별 수집 주기 확인(071)·설정(072) (FN-BAT-01, S15P31C106-318). 주기는 그 원천의 FULL_COMPARE 테이블에만 쓰고, 배치 {@link
 * ScheduledSyncService}가 주기마다 수집을 넣는다.
 *
 * <p>072는 관리 Action UPDATE_SYNC_SCHEDULE(권한 SYNC_SCHEDULE_MANAGE)이다. 성공하면 업무와 같은 트랜잭션에 ACTION 기록과
 * 바뀐 컬럼(entity_change)을 남기고, 권한·검증·버전에서 막히면 트랜잭션이 끝난 뒤 BLOCKED를 따로 남긴다(ActionDefinitionService와 같은
 * 방식).
 */
@Slf4j
@Service
public class SyncScheduleService {

    static final String UPDATE_ACTION = "UPDATE_SYNC_SCHEDULE";
    static final String MANAGE_PERMISSION = "SYNC_SCHEDULE_MANAGE";

    private static final String TABLE = "data_source";

    private final DataSourceRepository dataSourceRepository;
    private final AuditRecorder audits;
    private final ActionDefinitionLookup actionDefinitions;
    private final TransactionTemplate transactionTemplate;

    public SyncScheduleService(
            DataSourceRepository dataSourceRepository,
            AuditRecorder audits,
            ActionDefinitionLookup actionDefinitions,
            PlatformTransactionManager transactionManager) {
        this.dataSourceRepository = dataSourceRepository;
        this.audits = audits;
        this.actionDefinitions = actionDefinitions;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** API 071. */
    @Transactional(readOnly = true)
    public SyncScheduleResponse get(long dataSourceId) {
        return SyncScheduleResponse.from(findDataSource(dataSourceId));
    }

    /**
     * API 072. 보낸 값으로 바꾼다. syncEnabled가 false이고 syncIntervalMinutes를 생략하면 주기는 null이 된다. 값이 그대로면
     * rowVersion을 올리지 않고 entity_change도 남기지 않는다.
     */
    public SyncScheduleResponse update(
            LoginAccount actor, long dataSourceId, SyncScheduleRequest request) {
        Long definitionId = actionDefinitions.idOf(UPDATE_ACTION);
        Map<String, Object> input = input(dataSourceId, request);
        try {
            return transactionTemplate.execute(
                    tx -> updateInTransaction(actor, dataSourceId, request, definitionId, input));
        } catch (BusinessException ex) {
            recordBlocked(actor, definitionId, dataSourceId, input, ex);
            throw ex;
        }
    }

    private SyncScheduleResponse updateInTransaction(
            LoginAccount actor,
            long dataSourceId,
            SyncScheduleRequest request,
            Long definitionId,
            Map<String, Object> input) {
        if (!RoleCode.ADMIN.name().equals(actor.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
        if (request.syncEnabled() && request.syncIntervalMinutes() == null) {
            throw BusinessException.invalidField(
                    "syncIntervalMinutes", "syncEnabled가 true이면 수집 주기(분)가 필요합니다.");
        }
        DataSource source = findDataSource(dataSourceId);
        if (request.rowVersion() != source.getRowVersion()) {
            throw versionConflict(request.rowVersion(), source.getRowVersion());
        }
        Map<String, Object> before = snapshot(source);
        source.changeSyncSchedule(request.syncEnabled(), request.syncIntervalMinutes());
        // 응답의 rowVersion과 기록의 변경 후 값이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        try {
            dataSourceRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            // 버전을 비교한 뒤 다른 요청이 먼저 바꿨다.
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT, Map.of("field", "rowVersion"));
        }
        Map<String, Object> after = snapshot(source);

        AuditEntry.AuditEntryBuilder entry =
                AuditEntry.builder()
                        .eventKind(EventKind.ACTION)
                        .actor(Actor.user(actor))
                        .entryPoint(EntryPoint.WEB)
                        .status(ExecutionStatus.SUCCEEDED)
                        .actionDefinitionId(definitionId)
                        .checkedPermissionCode(MANAGE_PERMISSION)
                        .targetType(TABLE)
                        .targetId(dataSourceId)
                        .input(input)
                        .result(Map.of("dataSourceId", dataSourceId));
        List<ChangeEntry> changes = changes(dataSourceId, before, after);
        if (!changes.isEmpty()) {
            entry.changes(changes);
        }
        audits.record(entry.build());
        return SyncScheduleResponse.from(source);
    }

    private void recordBlocked(
            LoginAccount actor,
            Long definitionId,
            long dataSourceId,
            Map<String, Object> input,
            BusinessException ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorCode", ex.getErrorCode().code());
        result.put("details", ex.getDetails());
        boolean targetExists = ex.getErrorCode() != CommonErrorCode.RESOURCE_NOT_FOUND;
        try {
            audits.recordSeparately(
                    AuditEntry.builder()
                            .eventKind(EventKind.ACTION)
                            .actor(Actor.user(actor))
                            .entryPoint(EntryPoint.WEB)
                            .status(ExecutionStatus.BLOCKED)
                            .actionDefinitionId(definitionId)
                            .checkedPermissionCode(MANAGE_PERMISSION)
                            .targetType(targetExists ? TABLE : null)
                            .targetId(targetExists ? dataSourceId : null)
                            .input(input)
                            .result(result)
                            .blockedReason(blockedReason(ex))
                            .build());
        } catch (RuntimeException recordFailure) {
            // 기록 실패가 원래 오류 응답을 가리지 않게 한다.
            log.error("차단 기록 저장 실패: action={}", UPDATE_ACTION, recordFailure);
            ex.addSuppressed(recordFailure);
        }
    }

    private DataSource findDataSource(long id) {
        return dataSourceRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", TABLE, "id", id)));
    }

    private static BusinessException versionConflict(long expected, long actual) {
        return new BusinessException(
                CommonErrorCode.VERSION_CONFLICT,
                Map.of("field", "rowVersion", "expected", expected, "actual", actual));
    }

    private static Map<String, Object> input(long dataSourceId, SyncScheduleRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataSourceId", dataSourceId);
        input.put("syncEnabled", request.syncEnabled());
        input.put("syncIntervalMinutes", request.syncIntervalMinutes());
        input.put("rowVersion", request.rowVersion());
        return input;
    }

    private static String blockedReason(BusinessException ex) {
        Object reason = ex.getDetails().get("reason");
        return reason == null ? ex.getMessage() : ex.getMessage() + " " + reason;
    }

    /** entity_change에 남길 컬럼 값. */
    private static Map<String, Object> snapshot(DataSource source) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sync_enabled", source.isSyncEnabled());
        values.put("sync_interval_minutes", source.getSyncIntervalMinutes());
        values.put("row_version", source.getRowVersion());
        return values;
    }

    private static List<ChangeEntry> changes(
            long dataSourceId, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        before.forEach(
                (column, value) -> {
                    if (!Objects.equals(value, after.get(column))) {
                        changedBefore.put(column, value);
                        changedAfter.put(column, after.get(column));
                    }
                });
        if (changedAfter.isEmpty()) {
            return List.of();
        }
        return List.of(
                ChangeEntry.update(TABLE, Map.of("id", dataSourceId), changedBefore, changedAfter));
    }
}
