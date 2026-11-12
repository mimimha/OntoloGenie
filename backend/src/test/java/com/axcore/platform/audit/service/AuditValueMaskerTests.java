package com.axcore.platform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.action.domain.ActionDefinition;
import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.audit.domain.ActorType;
import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.domain.EntryPoint;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.domain.ExecutionCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.service.AuditValueMasker.Masked;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.auth.service.DataAccess;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditValueMaskerTests {

    private static final long PLAN_DEFINITION = 1L;
    private static final long OTHER_DEFINITION = 2L;

    private final DataAccess access = mock(DataAccess.class);
    private final ActionDefinitionRepository definitions = mock(ActionDefinitionRepository.class);
    private final AuditValueMasker masker =
            new AuditValueMasker(
                    List.of(
                            () ->
                                    List.of(
                                            Path.input(
                                                    "PLAN",
                                                    List.of("items", "*", "operationCode"),
                                                    "Operation",
                                                    "op_seq"),
                                            Path.change(
                                                    "plan_item",
                                                    List.of("source_snapshot", "workOrderNo"),
                                                    "WorkOrder",
                                                    "wo_no"),
                                            Path.change(
                                                    "plan_item",
                                                    List.of(
                                                            "source_snapshot",
                                                            "operations",
                                                            "*",
                                                            "status"),
                                                    "Operation",
                                                    "status"))),
                    definitions);

    @BeforeEach
    void definitions() {
        List<ActionDefinition> found =
                List.of(definition(PLAN_DEFINITION, "PLAN"), definition(OTHER_DEFINITION, "OTHER"));
        when(definitions.findAllById(any())).thenReturn(found);
    }

    @Test
    void hidesRestrictedValuesInChangesAndKeepsKeys() {
        when(access.hidesValue("WorkOrder", "wo_no")).thenReturn(true);
        when(access.hidesValue("Operation", "status")).thenReturn(true);
        AuditExecutionResponse row = row(PLAN_DEFINITION, input(), List.of(itemChange()));

        AuditExecutionResponse masked = masker.mask(List.of(row), access).getFirst();

        Map<String, Object> snapshot = snapshotOf(masked);
        assertThat(snapshot).containsEntry("workOrderNo", null);
        assertThat((List<?>) snapshot.get("operations"))
                .map(operation -> asMap(operation).get("sequence"))
                .containsExactly("20", "30");
        assertThat((List<?>) snapshot.get("operations"))
                .allSatisfy(
                        operation -> assertThat(asMap(operation)).containsEntry("status", null));
        assertThat(masked.input()).isEqualTo(input());
        assertThat(masked.restrictedPropertyCodes()).containsExactly("status", "wo_no");
        assertThat(masked.changeCount()).isOne();
        assertThat(snapshotOf(row)).containsEntry("workOrderNo", "WO-1");
    }

    @Test
    void masksInputAndChangesSeparatelyForASingleExecution() {
        when(access.hidesValue("Operation", "op_seq")).thenReturn(true);
        when(access.hidesValue("WorkOrder", "wo_no")).thenReturn(true);
        ActionDefinition plan = definition(PLAN_DEFINITION, "PLAN");
        when(definitions.findById(PLAN_DEFINITION)).thenReturn(Optional.of(plan));

        Masked<Map<String, Object>> input = masker.maskInput(PLAN_DEFINITION, input(), access);
        Masked<List<EntityChangeResponse>> changes =
                masker.maskChanges(List.of(itemChange()), access);

        assertThat((List<?>) input.value().get("items"))
                .allSatisfy(item -> assertThat(asMap(item)).containsEntry("operationCode", null));
        assertThat(input.restrictedPropertyCodes()).containsExactly("op_seq");
        assertThat(asMap(changes.value().getFirst().afterData().get("source_snapshot")))
                .containsEntry("workOrderNo", null);
        assertThat(changes.restrictedPropertyCodes()).containsExactly("wo_no");
        assertThat(masker.maskInput(OTHER_DEFINITION, input(), access).restrictedPropertyCodes())
                .isEmpty();
        assertThat(masker.maskInput(null, null, access))
                .isEqualTo(new Masked<Map<String, Object>>(null, List.of()));
    }

    @Test
    void hidesInputOnlyForTheDeclaringHandler() {
        when(access.hidesValue("Operation", "op_seq")).thenReturn(true);

        List<AuditExecutionResponse> masked =
                masker.mask(
                        List.of(
                                row(PLAN_DEFINITION, input(), List.of()),
                                row(OTHER_DEFINITION, input(), List.of())),
                        access);

        assertThat((List<?>) masked.get(0).input().get("items"))
                .allSatisfy(
                        item ->
                                assertThat(asMap(item))
                                        .containsEntry("operationCode", null)
                                        .containsEntry("workOrderId", 7));
        assertThat(masked.get(0).restrictedPropertyCodes()).containsExactly("op_seq");
        assertThat(masked.get(1).input()).isEqualTo(input());
        assertThat(masked.get(1).restrictedPropertyCodes()).isEmpty();
    }

    @Test
    void returnsRowAsIsWhenNothingIsHidden() {
        AuditExecutionResponse row = row(PLAN_DEFINITION, input(), List.of(itemChange()));

        assertThat(masker.mask(List.of(row), access)).containsExactly(row);
    }

    private static Map<String, Object> snapshotOf(AuditExecutionResponse row) {
        return asMap(row.entityChanges().getFirst().afterData().get("source_snapshot"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> input() {
        return Map.of(
                "fromEquipmentId",
                301,
                "items",
                List.of(Map.of("workOrderId", 7, "operationCode", "20")));
    }

    private static EntityChangeResponse itemChange() {
        return new EntityChangeResponse(
                "plan_item",
                Map.of("id", 1),
                ChangeKind.INSERT,
                null,
                Map.of(
                        "work_order_id",
                        7,
                        "source_snapshot",
                        Map.of(
                                "workOrderNo",
                                "WO-1",
                                "operations",
                                List.of(
                                        Map.of("objectId", 11, "sequence", "20", "status", "중단"),
                                        Map.of(
                                                "objectId",
                                                12,
                                                "sequence",
                                                "30",
                                                "status",
                                                "대기")))));
    }

    private static ActionDefinition definition(long id, String handlerKey) {
        ActionDefinition definition = mock(ActionDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getHandlerKey()).thenReturn(handlerKey);
        return definition;
    }

    private static AuditExecutionResponse row(
            long definitionId, Map<String, Object> input, List<EntityChangeResponse> changes) {
        return new AuditExecutionResponse(
                1L,
                UUID.randomUUID(),
                EventKind.ACTION,
                ExecutionCategory.OPERATION,
                definitionId,
                "CODE",
                "이름",
                ActorType.USER,
                3L,
                "계획 담당자",
                "PLANNER",
                "PLAN_CREATE",
                EntryPoint.WEB,
                "object_instance",
                301L,
                "프레스 3호기",
                input,
                ExecutionStatus.SUCCEEDED,
                null,
                Map.of("planId", 3),
                Instant.parse("2026-11-09T00:00:00Z"),
                Instant.parse("2026-11-09T00:00:01Z"),
                changes,
                changes.size(),
                List.of());
    }
}
