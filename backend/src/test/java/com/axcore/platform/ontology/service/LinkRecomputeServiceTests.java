package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.ontology.dto.LinkRecomputeResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연결 재계산(Jira 181, 결정 문서 9.4)을 실제 DB로 확인한다. 작업지시(출발).equipment_code를 TRIM·UPPER 해서 설비(도착).code와
 * 비교하는 규칙 하나를 기본으로 쓴다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class LinkRecomputeServiceTests {

    @Autowired LinkRecomputeService service;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    long resourceId;
    long workOrderType;
    long equipmentType;
    String workOrderTypeCode;
    String equipmentTypeCode;
    long equipmentMapping;
    long woEquipmentCode;
    long eqCode;
    long eqKind;
    long linkType;
    String ruleCode;
    long rule;

    @BeforeEach
    void setUp() {
        long sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values ('ERP-' || gen_random_uuid(), 'ERP', 'POSTGRES') returning id
                                """)
                        .query(Long.class)
                        .single();
        resourceId =
                jdbc.sql(
                                """
                                insert into source_resource
                                    (data_source_id, resource_key, resource_kind, sync_strategy)
                                values (:source, 'erp.equipment', 'TABLE', 'CDC') returning id
                                """)
                        .param("source", sourceId)
                        .query(Long.class)
                        .single();
        workOrderTypeCode = "WORK_ORDER_" + suffix();
        equipmentTypeCode = "EQUIPMENT_" + suffix();
        workOrderType = objectType(workOrderTypeCode);
        equipmentType = objectType(equipmentTypeCode);
        equipmentMapping =
                jdbc.sql(
                                """
                                insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                                values (:resource, :type, '["id"]') returning id
                                """)
                        .param("resource", resourceId)
                        .param("type", equipmentType)
                        .query(Long.class)
                        .single();
        woEquipmentCode = property(workOrderType, "equipment_code");
        eqCode = property(equipmentType, "code");
        eqKind = property(equipmentType, "kind");
        linkType = linkType("MANY_TO_ONE");
        ruleCode = "RULE_" + suffix();
        rule = rule(linkType, ruleCode, 1, "{}");
    }

    @Test
    void lateTargetCompletesLinkAndEvidenceKeepsMatchedValues() {
        long wo = workOrder(" eq-3 ");

        assertThat(recompute(wo).activatedLinkIds()).isEmpty();

        long eq = equipment("EQ-3");
        LinkRecomputeResult result = recompute(eq);

        assertThat(result.activatedLinkIds()).hasSize(1);
        Map<String, Object> link = link(result.activatedLinkIds().getFirst());
        assertThat(link)
                .containsEntry("source_object_id", wo)
                .containsEntry("target_object_id", eq)
                .containsEntry("origin", "RULE")
                .containsEntry("status", "ACTIVE")
                .containsEntry("link_mapping_id", rule);
        assertThat(String.valueOf(link.get("evidence")))
                .contains("\"ruleCode\": \"" + ruleCode + "\"")
                .contains("\"1\": \"EQ-3\"");
    }

    @Test
    void changedKeyMovesLinkAndInactivatesOldOne() {
        long wo = workOrder("EQ-3");
        long eq3 = equipment("EQ-3");
        long eq5 = equipment("EQ-5");
        long first = recompute(wo).activatedLinkIds().getFirst();

        setProperty(wo, "equipment_code", "EQ-5");
        LinkRecomputeResult result = recompute(wo);

        assertThat(result.inactivatedLinkIds()).containsExactly(first);
        assertThat(status(first)).isEqualTo("INACTIVE");
        assertThat(link(result.activatedLinkIds().getFirst()))
                .containsEntry("target_object_id", eq5);
        assertThat(activeTargets(wo)).containsExactly(eq5);
        assertThat(eq3).isNotEqualTo(eq5);
    }

    @Test
    void deletedAtSourceInactivatesAndRestoreReactivatesSameRow() {
        long wo = workOrder("EQ-3");
        long eq = equipment("EQ-3");
        long linkId = recompute(eq).activatedLinkIds().getFirst();

        setStatus(eq, "DELETED_AT_SOURCE");
        assertThat(recompute(eq).inactivatedLinkIds()).containsExactly(linkId);
        assertThat(hasInactivatedAt(linkId)).isTrue();

        setStatus(eq, "ACTIVE");
        LinkRecomputeResult restored = recompute(eq);

        assertThat(restored.activatedLinkIds()).containsExactly(linkId);
        assertThat(status(linkId)).isEqualTo("ACTIVE");
        assertThat(hasInactivatedAt(linkId)).isFalse();
        assertThat(activeTargets(wo)).containsExactly(eq);
    }

    @Test
    void ambiguousTargetForManyToOneIsNotLinkedAndExistingLinkIsDropped() {
        long wo = workOrder("EQ-3");
        equipment("EQ-3");
        long linkId = recompute(wo).activatedLinkIds().getFirst();

        long duplicate = equipment("EQ-3");
        LinkRecomputeResult result = recompute(duplicate);

        assertThat(result.inactivatedLinkIds()).containsExactly(linkId);
        assertThat(result.activatedLinkIds()).isEmpty();
        assertThat(activeTargets(wo)).isEmpty();
    }

    @Test
    void manualLinkIsNeverChangedByRule() {
        long wo = workOrder("EQ-3");
        long other = equipment("EQ-9");
        long account = account();
        long manual =
                jdbc.sql(
                                """
                                insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                                values (:type, :source, :target, 'MANUAL', :account) returning id
                                """)
                        .param("type", linkType)
                        .param("source", wo)
                        .param("target", other)
                        .param("account", account)
                        .query(Long.class)
                        .single();

        LinkRecomputeResult result = recompute(wo);

        assertThat(result.inactivatedLinkIds()).doesNotContain(manual);
        assertThat(status(manual)).isEqualTo("ACTIVE");
    }

    @Test
    void newRevisionRebindsLinksAndDropsThoseThatNoLongerMatch() {
        long wo = workOrder("EQ-3");
        long mold = equipment("EQ-3", "금형");
        long wo2 = workOrder("EQ-7");
        equipment("EQ-7", "프레스");
        // 관계 유형 전체를 계산하므로 한 번에 두 연결이 생긴다.
        assertThat(recompute(wo).activatedLinkIds()).hasSize(2);
        long kept = linkId(wo, mold);
        long dropped = linkId(wo2, activeTargets(wo2).getFirst());

        long revision2 =
                inTx(
                        () -> {
                            jdbc.sql(
                                            "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                                    .param("id", rule)
                                    .update();
                            return rule(
                                    linkType,
                                    ruleCode,
                                    2,
                                    "{\"property\": \""
                                            + equipmentTypeCode
                                            + ".kind\", \"equals\": \"금형\"}");
                        });
        LinkRecomputeResult result = inTx(() -> service.recomputeRules(List.of(revision2)));

        assertThat(result.updatedLinkIds()).containsExactly(kept);
        assertThat(result.inactivatedLinkIds()).containsExactly(dropped);
        assertThat(link(kept))
                .containsEntry("link_mapping_id", revision2)
                .containsEntry("target_object_id", mold);
    }

    @Test
    void unreadableFilterSkipsRuleInsteadOfFailing() {
        long odd = rule(linkType, "RULE_" + suffix(), 1, "{\"any\": [1, 2]}", "{}");
        long wo = workOrder("EQ-3");
        equipment("EQ-3");

        LinkRecomputeResult result = recompute(wo);

        assertThat(result.skippedRuleIds()).containsExactly(odd);
        assertThat(result.activatedLinkIds()).hasSize(1);
    }

    @Test
    void approvalPathFailsOnUnreadableRuleAndOtherRuleLinksAreKeptWhenSkipped() {
        long wo = workOrder("EQ-3");
        equipment("EQ-3");
        long linkId = recompute(wo).activatedLinkIds().getFirst();

        // 유형 코드가 도착 유형과 다른 조건은 읽지 못한다.
        long wrongPrefix =
                rule(
                        linkType,
                        "RULE_" + suffix(),
                        1,
                        "{}",
                        "{\"property\": \"" + workOrderTypeCode + ".kind\", \"equals\": \"금형\"}");

        assertThatThrownBy(() -> inTx(() -> service.recomputeRules(List.of(wrongPrefix))))
                .isInstanceOf(LinkRecomputeService.LinkRuleNotComputableException.class);

        // 같은 규칙이 CDC 경로에서는 건너뛰어지고, 다른 규칙의 연결은 그대로다.
        LinkRecomputeResult result = recompute(wo);
        assertThat(result.skippedRuleIds()).containsExactly(wrongPrefix);
        assertThat(status(linkId)).isEqualTo("ACTIVE");
    }

    @Test
    void deletingOneOfAmbiguousTargetsLinksTheRemainingPair() {
        long wo = workOrder("EQ-3");
        long keep = equipment("EQ-3");
        long gone = equipment("EQ-3");
        assertThat(recompute(gone).activatedLinkIds()).isEmpty();

        setStatus(gone, "DELETED_AT_SOURCE");
        LinkRecomputeResult result = recompute(gone);

        assertThat(result.activatedLinkIds()).hasSize(1);
        assertThat(activeTargets(wo)).containsExactly(keep);
    }

    @Test
    void movingAmbiguousTargetAwayLinksTheRemainingPair() {
        long wo = workOrder("EQ-3");
        long keep = equipment("EQ-3");
        long moved = equipment("EQ-3");
        assertThat(recompute(moved).activatedLinkIds()).isEmpty();

        setProperty(moved, "code", "EQ-4");
        recompute(moved);

        assertThat(activeTargets(wo)).containsExactly(keep);
    }

    @Test
    void oneToOneDoesNotLinkWhenTwoSourcesShareTarget() {
        long oneToOne = linkType("ONE_TO_ONE");
        rule(oneToOne, "RULE_" + suffix(), 1, "{}");
        long first = workOrder("EQ-3");
        long second = workOrder("EQ-3");
        equipment("EQ-3");

        recompute(first);

        assertThat(activeTargets(oneToOne, first)).isEmpty();
        assertThat(activeTargets(oneToOne, second)).isEmpty();
    }

    @Test
    void secondRuleOfSameLinkTypeKeepsPairWhenFirstStopsMatching() {
        // 두 번째 규칙: 작업지시.alt_code와 설비.code를 비교한다.
        long altCode = property(workOrderType, "alt_code");
        long second = secondRule(altCode);
        long wo = workOrder("EQ-3");
        setProperty(wo, "alt_code", "EQ-3");
        long eq = equipment("EQ-3");
        long linkId = recompute(wo).activatedLinkIds().getFirst();
        assertThat(link(linkId)).containsEntry("link_mapping_id", rule);

        setProperty(wo, "equipment_code", "EQ-9");
        LinkRecomputeResult result = recompute(wo);

        assertThat(result.inactivatedLinkIds()).isEmpty();
        assertThat(result.updatedLinkIds()).containsExactly(linkId);
        assertThat(link(linkId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("link_mapping_id", second)
                .containsEntry("target_object_id", eq);
    }

    @Test
    void twoRulesPointingOneSourceToDifferentTargetsLinkNeither() {
        long altCode = property(workOrderType, "alt_code");
        secondRule(altCode);
        long wo = workOrder("EQ-3");
        setProperty(wo, "alt_code", "EQ-4");
        equipment("EQ-3");
        equipment("EQ-4");

        recompute(wo);

        // MANY_TO_ONE인데 두 규칙이 서로 다른 설비를 가리키므로 어느 쪽도 잇지 않는다.
        assertThat(activeTargets(wo)).isEmpty();
    }

    @Test
    void skippedRuleLinkIsKeptUntilAnEndIsDeletedAtSource() {
        long wo = workOrder("EQ-3");
        long eq = equipment("EQ-3");
        long linkId = recompute(wo).activatedLinkIds().getFirst();
        // 규칙의 새 개정본이 읽을 수 없는 조건을 가진다(CDC 경로에서는 건너뛴다).
        inTx(
                () -> {
                    jdbc.sql(
                                    "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                            .param("id", rule)
                            .update();
                    return rule(linkType, ruleCode, 2, "{\"any\": [1]}", "{}");
                });

        assertThat(recompute(wo).inactivatedLinkIds()).isEmpty();
        assertThat(status(linkId)).isEqualTo("ACTIVE");

        setStatus(eq, "DELETED_AT_SOURCE");
        assertThat(recompute(eq).inactivatedLinkIds()).containsExactly(linkId);
    }

    @Test
    void manualLinkOnPairThatRuleWantsStaysManual() {
        long wo = workOrder("EQ-3");
        long eq = equipment("EQ-3");
        long manual =
                jdbc.sql(
                                """
                                insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                                values (:type, :source, :target, 'MANUAL', :account) returning id
                                """)
                        .param("type", linkType)
                        .param("source", wo)
                        .param("target", eq)
                        .param("account", account())
                        .query(Long.class)
                        .single();

        LinkRecomputeResult result = recompute(wo);

        assertThat(result.activatedLinkIds()).isEmpty();
        assertThat(link(manual))
                .containsEntry("origin", "MANUAL")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void objectTypeRecomputeCoversFullLoadWithoutPassingEveryId() {
        long wo = workOrder("EQ-3");
        long eq = equipment("EQ-3");

        LinkRecomputeResult result =
                inTx(() -> service.recomputeObjectTypes(List.of(equipmentType)));

        assertThat(result.activatedLinkIds()).hasSize(1);
        assertThat(activeTargets(wo)).containsExactly(eq);
    }

    @Test
    void newRevisionOfCurrentOwnerKeepsOwnershipEvenWhenAnotherRuleHasLowerId() {
        // 규칙 A(setUp의 rule) rev1이 연결의 근거. 규칙 B(alt_code)도 같은 쌍을 원하고 ID는 A rev2보다 작다.
        long altCode = property(workOrderType, "alt_code");
        secondRule(altCode);
        long wo = workOrder("EQ-3");
        setProperty(wo, "alt_code", "EQ-3");
        equipment("EQ-3");
        long linkId = recompute(wo).activatedLinkIds().getFirst();
        assertThat(link(linkId)).containsEntry("link_mapping_id", rule);

        long revision2 =
                inTx(
                        () -> {
                            jdbc.sql(
                                            "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                                    .param("id", rule)
                                    .update();
                            return rule(linkType, ruleCode, 2, "{}");
                        });
        LinkRecomputeResult result = inTx(() -> service.recomputeRules(List.of(revision2)));

        // 규칙 ID가 더 작은 B로 넘어가지 않고, 같은 코드 A의 새 개정본이 근거가 된다.
        assertThat(result.updatedLinkIds()).containsExactly(linkId);
        assertThat(link(linkId)).containsEntry("link_mapping_id", revision2);
    }

    @Test
    void recomputeRulesRejectsMissingOrRetiredRevision() {
        long retired =
                inTx(
                        () -> {
                            jdbc.sql(
                                            "update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                                    .param("id", rule)
                                    .update();
                            return rule;
                        });

        assertThatThrownBy(() -> inTx(() -> service.recomputeRules(List.of(retired))))
                .isInstanceOf(LinkRecomputeService.LinkRuleNotComputableException.class);
        assertThatThrownBy(() -> inTx(() -> service.recomputeRules(List.of(Long.MAX_VALUE))))
                .isInstanceOf(LinkRecomputeService.LinkRuleNotComputableException.class);
    }

    @Test
    void unlinkedSourcesListsWhyEachSourceIsNotLinked() {
        long linked = workOrder("EQ-3");
        long noTarget = workOrder(" eq-9 ");
        long empty = workOrder(" ");
        equipment("EQ-3");
        inTx(() -> service.recomputeRules(List.of(rule)));

        List<LinkRecomputeService.UnlinkedSource> unlinked =
                inTx(() -> service.unlinkedSources(rule));

        assertThat(unlinked)
                .extracting(LinkRecomputeService.UnlinkedSource::sourceObjectId)
                .contains(noTarget)
                .doesNotContain(linked, empty);
        LinkRecomputeService.UnlinkedSource item =
                unlinked.stream()
                        .filter(u -> u.sourceObjectId() == noTarget)
                        .findFirst()
                        .orElseThrow();
        assertThat(item.rawValue()).isEqualTo(" eq-9 ");
        assertThat(item.transformedValue()).isEqualTo("EQ-9");
        assertThat(item.reason()).isEqualTo("비교값이 같은 도착 객체가 없음");
    }

    @Test
    void refusesToRunOutsideCallerTransaction() {
        assertThatThrownBy(() -> service.recomputeObjects(List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.recomputeRules(List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private LinkRecomputeResult recompute(long objectId) {
        return inTx(() -> service.recomputeObjects(List.of(objectId)));
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private List<Long> activeTargets(long source) {
        return activeTargets(linkType, source);
    }

    private List<Long> activeTargets(long linkTypeId, long source) {
        return jdbc.sql(
                        """
                        select target_object_id from object_link
                        where link_type_id = :type and source_object_id = :source and status = 'ACTIVE'
                        order by target_object_id
                        """)
                .param("type", linkTypeId)
                .param("source", source)
                .query(Long.class)
                .list();
    }

    // 두 번째 규칙: 작업지시의 다른 속성과 설비.code를 비교한다(변환 없음).
    private long secondRule(long sourceProperty) {
        return inTx(
                () -> {
                    long id =
                            jdbc.sql(
                                            """
                                            insert into link_mapping (link_type_id, code, revision)
                                            values (:type, :code, 1) returning id
                                            """)
                                    .param("type", linkType)
                                    .param("code", "RULE_" + suffix())
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into link_mapping_condition
                                        (link_mapping_id, source_property_id, target_property_id, sequence_no)
                                    values (:rule, :source, :target, 1)
                                    """)
                            .param("rule", id)
                            .param("source", sourceProperty)
                            .param("target", eqCode)
                            .update();
                    return id;
                });
    }

    private long linkId(long source, long target) {
        return jdbc.sql(
                        """
                        select id from object_link
                        where link_type_id = :type and source_object_id = :source and target_object_id = :target
                        """)
                .param("type", linkType)
                .param("source", source)
                .param("target", target)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> link(long id) {
        return jdbc.sql(
                        """
                        select source_object_id, target_object_id, origin, status, link_mapping_id,
                               evidence::text as evidence
                        from object_link where id = :id
                        """)
                .param("id", id)
                .query()
                .singleRow();
    }

    private String status(long linkId) {
        return jdbc.sql("select status from object_link where id = :id")
                .param("id", linkId)
                .query(String.class)
                .single();
    }

    private boolean hasInactivatedAt(long linkId) {
        return jdbc.sql("select inactivated_at is not null from object_link where id = :id")
                .param("id", linkId)
                .query(Boolean.class)
                .single();
    }

    private long workOrder(String equipmentCode) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, 'WO', jsonb_build_object('equipment_code', cast(:code as text)))
                        returning id
                        """)
                .param("type", workOrderType)
                .param("code", equipmentCode)
                .query(Long.class)
                .single();
    }

    private long equipment(String code) {
        return equipment(code, "프레스");
    }

    private long equipment(String code, String kind) {
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties)
                        values (:type, :mapping, jsonb_build_object('id', gen_random_uuid()::text), 'EQ',
                                jsonb_build_object('code', cast(:code as text), 'kind', cast(:kind as text)))
                        returning id
                        """)
                .param("type", equipmentType)
                .param("mapping", equipmentMapping)
                .param("code", code)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    private void setProperty(long objectId, String code, String value) {
        jdbc.sql(
                        """
                        update object_instance
                        set properties = jsonb_set(properties, array[cast(:code as text)], to_jsonb(cast(:value as text)))
                        where id = :id
                        """)
                .param("code", code)
                .param("value", value)
                .param("id", objectId)
                .update();
    }

    private void setStatus(long objectId, String status) {
        jdbc.sql("update object_instance set status = :status where id = :id")
                .param("status", status)
                .param("id", objectId)
                .update();
    }

    private long objectType(String code) {
        return jdbc.sql("insert into object_type (code, name) values (:code, :code) returning id")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long property(long objectTypeId, String code) {
        return jdbc.sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:type, :code, :code, 'STRING') returning id
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long linkType(String cardinality) {
        return jdbc.sql(
                        """
                        insert into link_type (code, name, source_type_id, target_type_id, cardinality, description)
                        values (:code, :code, :source, :target, :cardinality, '재계산 테스트') returning id
                        """)
                .param("code", "LINK_" + suffix())
                .param("source", workOrderType)
                .param("target", equipmentType)
                .param("cardinality", cardinality)
                .query(Long.class)
                .single();
    }

    private long rule(long linkTypeId, String code, int revision, String targetFilter) {
        return rule(linkTypeId, code, revision, "{}", targetFilter);
    }

    // 비교 항목은 규칙을 만든 트랜잭션 안에서만 넣을 수 있다(DB 트리거).
    private long rule(
            long linkTypeId, String code, int revision, String sourceFilter, String targetFilter) {
        return inTx(
                () -> {
                    long id =
                            jdbc.sql(
                                            """
                                            insert into link_mapping
                                                (link_type_id, code, revision, source_filter, target_filter)
                                            values (:type, :code, :revision, cast(:source as jsonb), cast(:target as jsonb))
                                            returning id
                                            """)
                                    .param("type", linkTypeId)
                                    .param("code", code)
                                    .param("revision", revision)
                                    .param("source", sourceFilter)
                                    .param("target", targetFilter)
                                    .query(Long.class)
                                    .single();
                    jdbc.sql(
                                    """
                                    insert into link_mapping_condition
                                        (link_mapping_id, source_property_id, target_property_id, sequence_no,
                                         source_transforms)
                                    values (:rule, :source, :target, 1,
                                            '[{"op": "TRIM", "arg": null}, {"op": "UPPER", "arg": null}]')
                                    """)
                            .param("rule", id)
                            .param("source", woEquipmentCode)
                            .param("target", eqCode)
                            .update();
                    return id;
                });
    }

    private long account() {
        return jdbc.sql(
                        """
                        insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                        values (:login, '연결 테스트', 'test-hash', (select id from role where code = 'ADMIN'), false)
                        returning id
                        """)
                .param("login", "link-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
