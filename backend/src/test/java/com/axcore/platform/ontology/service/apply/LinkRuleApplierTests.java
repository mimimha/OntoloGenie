package com.axcore.platform.ontology.service.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.service.LinkRecomputeService;
import com.axcore.platform.ontology.service.ProposalApplyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LINK_RULE 후보 반영(Jira 189 2단계·186)을 실제 DB로 확인한다. 작업지시(출발).equipment_code와 설비(도착).code를 비교하는 규칙을
 * 쓴다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class LinkRuleApplierTests {

    @Autowired ProposalApplyService service;
    @Autowired LinkRecomputeService recompute;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    long account;
    String workOrderType;
    String equipmentType;
    long workOrderTypeId;
    long equipmentTypeId;
    String linkTypeCode;
    long linkTypeId;
    String ruleCode;

    @BeforeEach
    void setUp() {
        account =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '규칙 반영', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "rule-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        workOrderType = "WORK_ORDER_" + suffix();
        equipmentType = "EQUIPMENT_" + suffix();
        workOrderTypeId = objectType(workOrderType);
        equipmentTypeId = objectType(equipmentType);
        property(workOrderTypeId, "equipment_code");
        property(equipmentTypeId, "code");
        property(equipmentTypeId, "kind");
        linkTypeCode = "WO_EQ_" + suffix();
        linkTypeId =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '작업지시의 설비', :source, :target, 'MANY_TO_ONE', '규칙 반영 테스트') returning id
                                """)
                        .param("code", linkTypeCode)
                        .param("source", workOrderTypeId)
                        .param("target", equipmentTypeId)
                        .query(Long.class)
                        .single();
        ruleCode = "RULE_" + suffix();
    }

    @Test
    void firstRevisionLinksMatchesAndHoldsTheRest() {
        long linked = workOrder("EQ-3");
        long held = workOrder("W23");
        long empty = workOrder(" ");
        long eq = equipment("EQ-3", "프레스");
        long proposal = proposal();

        ApplyResult result = apply(proposal, rulePayload(List.of()));

        assertThat(result.targetTable()).isEqualTo("link_mapping");
        assertThat(
                        row(
                                "select code, revision, status, origin, proposal_item_id, created_by from link_mapping where id = :id",
                                result.targetId()))
                .containsEntry("code", ruleCode)
                .containsEntry("revision", 1)
                .containsEntry("status", "ACTIVE")
                .containsEntry("origin", "MANUAL")
                .containsEntry("proposal_item_id", proposal)
                .containsEntry("created_by", account);
        assertThat(count("link_mapping_condition", "link_mapping_id = " + result.targetId()))
                .isEqualTo(1);
        assertThat(
                        count(
                                "object_link",
                                "link_type_id = "
                                        + linkTypeId
                                        + " and source_object_id = "
                                        + linked
                                        + " and target_object_id = "
                                        + eq
                                        + " and status = 'ACTIVE' and link_mapping_id = "
                                        + result.targetId()))
                .isEqualTo(1);

        assertThat(result.heldItemIds()).hasSize(1);
        assertThat(
                        row(
                                "select source_object_id, raw_value, transformed_value, hold_reason, status, link_mapping_id, proposal_item_id from held_item where id = :id",
                                result.heldItemIds().getFirst()))
                .containsEntry("source_object_id", held)
                .containsEntry("raw_value", "W23")
                .containsEntry("transformed_value", "W23")
                .containsEntry("hold_reason", "비교값이 같은 도착 객체가 없음")
                .containsEntry("status", "HELD")
                .containsEntry("link_mapping_id", result.targetId())
                .containsEntry("proposal_item_id", proposal);
        assertThat(count("held_item", "source_object_id = " + empty)).isZero();
        assertThat(result.summary())
                .containsEntry("revision", 1)
                .containsEntry("linksActivated", 1)
                .containsEntry("heldCount", 1);
    }

    @Test
    void newRevisionRetiresOldOneRebindsLinksAndDoesNotDuplicateHeldItems() {
        // 운영처럼 ID가 Long 캐시(-128~127) 밖이어도 닫기가 맞는지 본다.
        jdbc.sql(
                        """
                        select setval(pg_get_serial_sequence('link_mapping', 'id'),
                                      greatest(1000, nextval(pg_get_serial_sequence('link_mapping', 'id'))))
                        """)
                .query()
                .singleValue();
        long wo = workOrder("eq-3");
        long held = workOrder("W23");
        equipment("EQ-3", "프레스");
        ApplyResult first = apply(proposal(), rulePayload(List.of()));
        assertThat(count("held_item", "source_object_id in (" + wo + "," + held + ")"))
                .isEqualTo(2);

        // 두 번째 개정본은 UPPER로 wo를 잇는다. W23은 여전히 대상이 없지만 열린 보류 건이 있어 다시 만들지 않는다.
        ApplyResult second = apply(proposal(), rulePayload(List.of(Map.of("op", "UPPER"))));

        assertThat(row("select status from link_mapping where id = :id", first.targetId()))
                .containsEntry("status", "RETIRED");
        assertThat(
                        row(
                                "select revision, status from link_mapping where id = :id",
                                second.targetId()))
                .containsEntry("revision", 2)
                .containsEntry("status", "ACTIVE");
        assertThat(activeTargets(wo)).hasSize(1);
        assertThat(second.heldItemIds()).isEmpty();
        assertThat(count("held_item", "source_object_id = " + held)).isEqualTo(1);
        assertThat(second.summary()).containsEntry("retiredRevisionId", first.targetId());

        // rev1에서 보류했던 wo는 rev2로 이어졌으므로 승인자가 닫은 것으로 기록된다. W23은 여전히 열려 있다.
        assertThat(second.summary()).containsEntry("heldClosedCount", 1);
        Map<String, Object> closed =
                jdbc.sql(
                                """
                                select h.status, h.resolved_by, h.resolution_note, h.resolved_at is not null as resolved,
                                       h.resolved_link_id = l.id as same_link
                                from held_item h
                                join object_link l on l.link_type_id = h.link_type_id and l.source_object_id = h.source_object_id
                                where h.source_object_id = :wo
                                """)
                        .param("wo", wo)
                        .query()
                        .singleRow();
        assertThat(closed)
                .containsEntry("status", "APPROVED")
                .containsEntry("resolved_by", account)
                .containsEntry("resolved", true)
                .containsEntry("same_link", true);
        assertThat(String.valueOf(closed.get("resolution_note"))).contains("개정본 2");
        assertThat(row("select status from held_item where source_object_id = :id", held))
                .containsEntry("status", "HELD");
        assertThat(second.changes()).extracting("tableName").contains("held_item");
    }

    @Test
    void sourceDroppedByAnotherRuleOfSameLinkTypeBecomesHeldItem() {
        // 규칙 A(equipment_code)로 WO1→EQ-1이 이어진 뒤, 규칙 B(alt_code)는 WO1→EQ-2를 원한다. MANY_TO_ONE이라 둘 다
        // 빠진다.
        property(workOrderTypeId, "alt_code");
        long wo =
                jdbc.sql(
                                """
                                insert into object_instance (object_type_id, display_name, properties)
                                values (:type, 'WO', '{"equipment_code": "EQ-1", "alt_code": "EQ-2"}'::jsonb) returning id
                                """)
                        .param("type", workOrderTypeId)
                        .query(Long.class)
                        .single();
        long eq1 = equipment("EQ-1", "프레스");
        equipment("EQ-2", "프레스");
        ApplyResult ruleA = apply(proposal(), rulePayload(List.of()));
        assertThat(activeTargets(wo)).hasSize(1);

        String ruleB = "RULE_B_" + suffix();
        Map<String, Object> other = rulePayload(List.of());
        other.put("code", ruleB);
        other.put(
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                workOrderType + ".alt_code",
                                "targetProperty",
                                "code")));
        ApplyResult result = apply(proposal(), other);

        assertThat(activeTargets(wo)).isEmpty();
        assertThat(result.summary()).containsEntry("linksInactivated", 1);
        assertThat(result.heldItemIds()).hasSize(1);
        assertThat(
                        row(
                                "select source_object_id, hold_reason from held_item where id = :id",
                                result.heldItemIds().getFirst()))
                .containsEntry("source_object_id", wo)
                .containsEntry("hold_reason", "이 규칙으로는 이어지지만 같은 관계 유형의 다른 규칙·연결과 맞지 않아 잇지 않음");

        // 규칙 B 개정본 2가 A와 같은 쌍(WO1→EQ-1)을 원하면 다시 이어진다. 근거는 기존 연결의 규칙(A)을 우선해 A로 남지만, 이번
        // 승인으로 이어졌으므로 B가 열어 둔 보류 건은 닫힌다.
        Map<String, Object> sameAsA = rulePayload(List.of());
        sameAsA.put("code", ruleB);
        ApplyResult second = apply(proposal(), sameAsA);

        assertThat(activeTargets(wo)).containsExactly(eq1);
        assertThat(
                        count(
                                "object_link",
                                "source_object_id = "
                                        + wo
                                        + " and status = 'ACTIVE' and link_mapping_id = "
                                        + ruleA.targetId()))
                .isEqualTo(1);
        assertThat(second.summary()).containsEntry("heldClosedCount", 1);
        assertThat(
                        row(
                                "select status from held_item where id = :id",
                                result.heldItemIds().getFirst()))
                .containsEntry("status", "APPROVED");
    }

    @Test
    void heldItemAlreadyLinkedByCollectionIsClosedWithItsOwnNote() {
        long wo = workOrder("W23");
        ApplyResult ruleA = apply(proposal(), rulePayload(List.of()));
        long heldId = ruleA.heldItemIds().getFirst();

        // 수집이 도착을 적재하고 다시 계산해 이었지만 자동 해소가 아직 없어 보류 건은 열려 있다.
        long eq = equipment("W23", "프레스");
        tx.executeWithoutResult(status -> recompute.recomputeRules(List.of(ruleA.targetId())));
        assertThat(activeTargets(wo)).containsExactly(eq);
        assertThat(row("select status from held_item where id = :id", heldId))
                .containsEntry("status", "HELD");

        // 같은 관계 유형의 다른 규칙을 승인하면 기존 연결을 근거로 닫되, 이번 승인으로 이어진 것과 메모를 달리한다.
        property(workOrderTypeId, "alt_code");
        Map<String, Object> other = rulePayload(List.of());
        other.put("code", "RULE_C_" + suffix());
        other.put(
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                workOrderType + ".alt_code",
                                "targetProperty",
                                "code")));
        ApplyResult ruleC = apply(proposal(), other);

        assertThat(ruleC.summary()).containsEntry("heldClosedCount", 1);
        assertThat(row("select status, resolution_note from held_item where id = :id", heldId))
                .containsEntry("status", "APPROVED")
                .containsEntry(
                        "resolution_note",
                        "규칙 " + other.get("code") + " 개정본 1 승인 때 이미 규칙 연결이 있어 닫음");
    }

    @Test
    void sourceFilterLimitsLinkedAndHeldSources() {
        long matching = workOrder("EQ-3");
        long outside = workOrder("W23");
        long eq = equipment("EQ-3", "프레스");
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("sourceFilter", Map.of("property", "equipment_code", "equals", "EQ-3"));

        ApplyResult result = apply(proposal(), payload);

        assertThat(activeTargets(matching)).containsExactly(eq);
        assertThat(result.heldItemIds()).isEmpty();
        assertThat(count("held_item", "source_object_id = " + outside)).isZero();
    }

    @Test
    void selfRelationDoesNotMatchTheSourceItself() {
        long lonely = equipment("EQ-9", "프레스");
        String selfLinkType = "EQ_EQ_" + suffix();
        jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, '같은 코드 설비', :type, :type, 'MANY_TO_MANY', '자기 관계') returning id
                        """)
                .param("code", selfLinkType)
                .param("type", equipmentTypeId)
                .query(Long.class)
                .single();
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("linkTypeCode", selfLinkType);
        payload.put(
                "conditions", List.of(Map.of("sourceProperty", "code", "targetProperty", "code")));

        ApplyResult result = apply(proposal(), payload);

        assertThat(result.heldItemIds()).hasSize(1);
        assertThat(
                        row(
                                "select source_object_id, hold_reason from held_item where id = :id",
                                result.heldItemIds().getFirst()))
                .containsEntry("source_object_id", lonely)
                .containsEntry("hold_reason", "비교값이 같은 도착 객체가 없음");
    }

    @Test
    void aiBundleCreatesMissingLinkTypeFirst() {
        String newLinkType = "NEW_LINK_" + suffix();
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("linkTypeCode", newLinkType);
        payload.put(
                "linkType",
                Map.of(
                        "code",
                        newLinkType,
                        "name",
                        "새 관계",
                        "sourceTypeCode",
                        workOrderType,
                        "targetTypeCode",
                        equipmentType,
                        "cardinality",
                        "MANY_TO_ONE",
                        "description",
                        ""));

        ApplyResult result = apply(proposal(), payload);

        assertThat(count("link_type", "code = '" + newLinkType + "' and description = '새 관계'"))
                .isEqualTo(1);
        assertThat(result.changes()).extracting("tableName").contains("link_type", "link_mapping");
    }

    @Test
    void targetFilterLimitsCandidateTargets() {
        long filteredOut = workOrder("W99");
        Map<String, Object> payload = rulePayload(List.of());
        payload.put("targetFilter", Map.of("property", equipmentType + ".kind", "equals", "금형"));
        long mold = equipment("EQ-3", "금형");
        long wo = workOrder("EQ-3");
        equipment("EQ-4", "프레스");

        ApplyResult result = apply(proposal(), payload);

        assertThat(activeTargets(wo)).containsExactly(mold);
        assertThat(result.heldItemIds()).hasSize(1);
        assertThat(count("held_item", "source_object_id = " + filteredOut)).isEqualTo(1);
    }

    @Test
    void invalidPayloadsAreRejectedBeforeAnyWrite() {
        Map<String, Object> wrongPrefix = rulePayload(List.of());
        wrongPrefix.put(
                "conditions",
                List.of(
                        Map.of(
                                "sourceProperty",
                                equipmentType + ".code",
                                "targetProperty",
                                "code")));
        assertApplyError(
                wrongPrefix,
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.conditions[0].sourceProperty");

        Map<String, Object> noLinkType = rulePayload(List.of());
        noLinkType.put("linkTypeCode", "NOT_APPROVED_" + suffix());
        assertApplyError(
                noLinkType, ApplyErrorCode.APPLY_REFERENCE_MISSING, "payload.linkTypeCode");

        Map<String, Object> badFilter = rulePayload(List.of());
        badFilter.put("sourceFilter", Map.of("any", List.of(1)));
        assertApplyError(badFilter, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.sourceFilter");

        Map<String, Object> badTransform =
                rulePayload(List.of(Map.of("op", "PAD_ZEROS", "arg", "x")));
        assertApplyError(
                badTransform,
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.conditions[0].sourceTransforms");

        Map<String, Object> noConditions = rulePayload(List.of());
        noConditions.put("conditions", List.of());
        assertApplyError(noConditions, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.conditions");

        assertThat(count("link_mapping", "code = '" + ruleCode + "'")).isZero();
    }

    @Test
    void sameRuleCodeMustStayOnItsLinkType() {
        apply(proposal(), rulePayload(List.of()));
        long otherLinkType =
                jdbc.sql(
                                """
                                insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                                values (:code, '다른 관계', :source, :target, 'MANY_TO_MANY', '다른 관계') returning id
                                """)
                        .param("code", "OTHER_" + suffix())
                        .param("source", workOrderTypeId)
                        .param("target", equipmentTypeId)
                        .query(Long.class)
                        .single();
        Map<String, Object> payload = rulePayload(List.of());
        payload.remove("linkTypeCode");
        payload.put("linkTypeId", otherLinkType);

        assertApplyError(payload, ApplyErrorCode.APPLY_PAYLOAD_INVALID, "payload.linkTypeCode");
    }

    private Map<String, Object> rulePayload(List<Map<String, Object>> sourceTransforms) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("sourceProperty", workOrderType + ".equipment_code");
        condition.put("targetProperty", "code");
        condition.put("operator", "EQ");
        condition.put("sourceTransforms", sourceTransforms);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", ruleCode);
        payload.put("linkTypeCode", linkTypeCode);
        payload.put("pattern", "EXACT");
        payload.put("conditions", List.of(condition));
        payload.put("description", "작업지시의 설비 코드로 설비 연결");
        return payload;
    }

    private ApplyResult apply(long proposal, Map<String, Object> payload) {
        return tx.execute(
                status ->
                        service.apply(
                                new ApplyCommand(
                                        proposal,
                                        ProposalItemKind.LINK_RULE,
                                        ProposalOrigin.MANUAL,
                                        payload,
                                        account)));
    }

    private void assertApplyError(
            Map<String, Object> payload, ApplyErrorCode code, String fieldPrefix) {
        long proposal = proposal();
        ThrowingCallable call = () -> apply(proposal, payload);
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(String.valueOf(e.getDetails().get("field")))
                                    .startsWith(fieldPrefix);
                        });
    }

    private List<Long> activeTargets(long source) {
        return jdbc.sql(
                        """
                        select target_object_id from object_link
                        where link_type_id = :type and source_object_id = :source and status = 'ACTIVE'
                        """)
                .param("type", linkTypeId)
                .param("source", source)
                .query(Long.class)
                .list();
    }

    // 후보는 첫 개정본과 같은 트랜잭션에서 넣어야 한다(지연 FK fk_proposal_item_current_revision).
    private long proposal() {
        return tx.execute(
                status -> {
                    long id =
                            jdbc.sql(
                                            """
                                            insert into proposal_item (origin, item_kind, payload, created_by)
                                            values ('MANUAL', 'LINK_RULE', '{}'::jsonb, :actor) returning id
                                            """)
                                    .param("actor", account)
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into proposal_revision (proposal_item_id, revision, payload, created_by)
                                    values (:id, 1, '{}'::jsonb, :actor)
                                    """)
                            .param("id", id)
                            .param("actor", account)
                            .update();
                    return id;
                });
    }

    private long workOrder(String equipmentCode) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, 'WO', jsonb_build_object('equipment_code', cast(:code as text))) returning id
                        """)
                .param("type", workOrderTypeId)
                .param("code", equipmentCode)
                .query(Long.class)
                .single();
    }

    private long equipment(String code, String kind) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, 'EQ', jsonb_build_object('code', cast(:code as text), 'kind', cast(:kind as text)))
                        returning id
                        """)
                .param("type", equipmentTypeId)
                .param("code", code)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    private long objectType(String code) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :code) returning id")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void property(long objectTypeId, String code) {
        jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:type, :code, :code, 'STRING')
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .update();
    }

    private Map<String, Object> row(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query().singleRow();
    }

    private long count(String table, String where) {
        return jdbc.sql("select count(*) from " + table + " where " + where)
                .query(Long.class)
                .single();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
