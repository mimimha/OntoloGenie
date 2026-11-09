package com.axcore.platform.ontology.service.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.domain.ProposalOrigin;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
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
 * 승인 후보 반영 1단계(Jira 189): 정의·매핑 5종 반영기를 실제 DB로 확인한다. 승인 흐름(131)처럼 트랜잭션 하나를 열고 {@link
 * ProposalApplyService}를 부른다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DefinitionAppliersTests {

    @Autowired ProposalApplyService service;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    String sourceCode;
    long resourceId;
    String resourceKey;
    long account;

    @BeforeEach
    void setUp() {
        sourceCode = "ERP_" + suffix();
        long sourceId =
                jdbc.sql(
                                "insert into data_source (code, name, source_kind) values (:code, 'ERP', 'POSTGRES') returning id")
                        .param("code", sourceCode)
                        .query(Long.class)
                        .single();
        resourceKey = "erp.equipment_" + suffix().substring(0, 8);
        resourceId =
                jdbc.sql(
                                """
                                insert into source_resource (data_source_id, resource_key, resource_kind, sync_strategy, loaded_lsn)
                                values (:source, :key, 'TABLE', 'CDC', 100) returning id
                                """)
                        .param("source", sourceId)
                        .param("key", resourceKey)
                        .query(Long.class)
                        .single();
        column("eq_id", "varchar", 1, true);
        column("eq_name", "varchar", 2, false);
        account =
                jdbc.sql(
                                """
                                insert into account(login_id, display_name, password_hash, role_id, must_change_password)
                                values (:login, '반영 테스트', 'test-hash', (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("login", "apply-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void aiBundleCreatesTypePropertiesAndMappingsAndRequestsReload() {
        String code = "EQUIPMENT_" + suffix().substring(0, 8).toUpperCase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", "설비");
        payload.put("description", "생산 설비");
        payload.put("synonyms", List.of("장비"));
        payload.put(
                "objectMapping",
                Map.of(
                        "sourceId",
                        sourceCode,
                        "resourceKey",
                        resourceKey,
                        "keyColumns",
                        List.of("eq_id")));
        payload.put(
                "properties",
                List.of(
                        Map.of(
                                "code",
                                "code",
                                "name",
                                "설비 코드",
                                "dataType",
                                "STRING",
                                "isIdentifier",
                                true,
                                "sourceColumn",
                                Map.of(
                                        "sourceId", sourceCode,
                                        "resourceKey", resourceKey,
                                        "column", "eq_id"),
                                "transformSteps",
                                List.of(Map.of("op", "UPPER"))),
                        Map.of(
                                "code", "name",
                                "name", "설비 이름",
                                "dataType", "STRING",
                                "sensitivity", "NORMAL",
                                "sourceColumn",
                                        Map.of(
                                                "sourceId", sourceCode,
                                                "resourceKey", resourceKey,
                                                "column", "eq_name")),
                        Map.of("code", "note", "name", "비고", "dataType", "STRING")));
        long proposal = proposal("OBJECT_TYPE");

        ApplyResult result = apply(proposal, ProposalItemKind.OBJECT_TYPE, payload);

        assertThat(result.targetTable()).isEqualTo("object_type");
        Map<String, Object> type =
                row(
                        "select code, origin, proposal_item_id, description from object_type where id = :id",
                        result.targetId());
        assertThat(type)
                .containsEntry("code", code)
                .containsEntry("origin", "MANUAL")
                .containsEntry("proposal_item_id", proposal)
                .containsEntry("description", "생산 설비");
        assertThat(
                        count(
                                "property_definition",
                                "object_type_id = "
                                        + result.targetId()
                                        + " and proposal_item_id = "
                                        + proposal))
                .isEqualTo(3);
        assertThat(
                        count(
                                "object_source_mapping",
                                "object_type_id = "
                                        + result.targetId()
                                        + " and is_active and source_resource_id = "
                                        + resourceId))
                .isEqualTo(1);
        assertThat(
                        count(
                                "property_source_mapping psm join property_definition p on p.id = psm.property_definition_id",
                                "p.object_type_id = " + result.targetId()))
                .isEqualTo(2);
        assertThat(result.reloadResourceIds()).containsExactly(resourceId);
        assertThat(count("source_resource", "id = " + resourceId + " and loaded_lsn is null"))
                .isEqualTo(1);
        assertThat(result.changes()).hasSize(1 + 3 + 1 + 2);
        assertThat(result.summary())
                .containsEntry("propertyCount", 3)
                .containsEntry("propertyMappingCount", 2);
    }

    @Test
    void bundleFailureRollsBackEveryPart() {
        String code = "BROKEN_" + suffix().substring(0, 8).toUpperCase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", "깨진 묶음");
        payload.put(
                "objectMapping",
                Map.of(
                        "sourceId",
                        sourceCode,
                        "resourceKey",
                        resourceKey,
                        "keyColumns",
                        List.of("eq_id")));
        payload.put(
                "properties",
                List.of(
                        Map.of(
                                "code", "code",
                                "name", "코드",
                                "dataType", "STRING",
                                "sourceColumn",
                                        Map.of(
                                                "sourceId", sourceCode,
                                                "resourceKey", resourceKey,
                                                "column", "no_such_column"))));
        long proposal = proposal("OBJECT_TYPE");

        assertApplyError(
                () -> apply(proposal, ProposalItemKind.OBJECT_TYPE, payload),
                ApplyErrorCode.APPLY_REFERENCE_MISSING,
                "payload.properties[0].sourceColumn.column");
        assertThat(count("object_type", "code = '" + code + "'")).isZero();
        assertThat(count("source_resource", "id = " + resourceId + " and loaded_lsn is null"))
                .isZero();
    }

    @Test
    void duplicateCodeIsTargetExists() {
        String code = "DUP_" + suffix().substring(0, 8).toUpperCase();
        jdbc.sql("insert into object_type (code, name) values (:code, :code)")
                .param("code", code)
                .update();

        assertApplyError(
                () ->
                        apply(
                                proposal("OBJECT_TYPE"),
                                ProposalItemKind.OBJECT_TYPE,
                                Map.of("code", code, "name", "중복")),
                ApplyErrorCode.APPLY_TARGET_EXISTS,
                "payload.code");
    }

    @Test
    void propertyNeedsItsTypeFirstAndValidValues() {
        assertApplyError(
                () ->
                        apply(
                                proposal("PROPERTY"),
                                ProposalItemKind.PROPERTY,
                                Map.of(
                                        "objectTypeCode", "NOT_APPROVED_YET",
                                        "code", "code",
                                        "name", "코드",
                                        "dataType", "STRING")),
                ApplyErrorCode.APPLY_REFERENCE_MISSING,
                "payload.objectTypeCode");

        String type = objectType();
        assertApplyError(
                () ->
                        apply(
                                proposal("PROPERTY"),
                                ProposalItemKind.PROPERTY,
                                Map.of(
                                        "objectTypeCode", type,
                                        "code", "code",
                                        "name", "코드",
                                        "dataType", "TEXT")),
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload.dataType");

        ApplyResult created =
                apply(
                        proposal("PROPERTY"),
                        ProposalItemKind.PROPERTY,
                        Map.of(
                                "objectTypeCode", type,
                                "code", "code",
                                "name", "코드",
                                "dataType", "STRING",
                                "isRequired", true));
        assertThat(
                        row(
                                "select is_required from property_definition where id = :id",
                                created.targetId()))
                .containsEntry("is_required", true);
    }

    @Test
    void linkTypeUsesNameWhenDescriptionIsBlank() {
        String source = objectType();
        String target = objectType();
        String code = "LINK_" + suffix().substring(0, 8).toUpperCase();

        ApplyResult result =
                apply(
                        proposal("LINK_TYPE"),
                        ProposalItemKind.LINK_TYPE,
                        Map.of(
                                "code",
                                code,
                                "name",
                                "작업지시의 설비",
                                "sourceTypeCode",
                                source,
                                "targetTypeCode",
                                target,
                                "cardinality",
                                "MANY_TO_ONE",
                                "description",
                                ""));

        assertThat(
                        row(
                                "select description, cardinality from link_type where id = :id",
                                result.targetId()))
                .containsEntry("description", "작업지시의 설비")
                .containsEntry("cardinality", "MANY_TO_ONE");
    }

    @Test
    void separateMappingsFollowTheirPrerequisites() {
        String type = objectType();
        long typeId =
                jdbc.sql("select id from object_type where code = :code")
                        .param("code", type)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "insert into property_definition (object_type_id, code, name, data_type) values (:type, 'code', '코드', 'STRING')")
                .param("type", typeId)
                .update();

        // 객체 매핑이 없으면 속성 매핑을 반영할 수 없다.
        Map<String, Object> propertyMapping =
                Map.of(
                        "objectTypeCode",
                        type,
                        "propertyCode",
                        "code",
                        "sourceColumn",
                        Map.of(
                                "sourceId",
                                sourceCode,
                                "resourceKey",
                                resourceKey,
                                "column",
                                "eq_id"),
                        "transformSteps",
                        List.of(Map.of("op", "TRIM", "arg", Map.of())));
        assertApplyError(
                () ->
                        apply(
                                proposal("PROPERTY_MAPPING"),
                                ProposalItemKind.PROPERTY_MAPPING,
                                propertyMapping),
                ApplyErrorCode.APPLY_REFERENCE_MISSING,
                "payload.sourceColumn");

        ApplyResult mapping =
                apply(
                        proposal("OBJECT_MAPPING"),
                        ProposalItemKind.OBJECT_MAPPING,
                        Map.of(
                                "objectTypeCode", type,
                                "sourceId", sourceCode,
                                "resourceKey", resourceKey,
                                "keyColumns", List.of("eq_id")));
        assertThat(mapping.reloadResourceIds()).containsExactly(resourceId);

        // TRIM에 인자를 주면 변환 검증에서 막힌다.
        assertApplyError(
                () ->
                        apply(
                                proposal("PROPERTY_MAPPING"),
                                ProposalItemKind.PROPERTY_MAPPING,
                                propertyMapping),
                ApplyErrorCode.APPLY_PAYLOAD_INVALID,
                "payload");

        Map<String, Object> valid = new LinkedHashMap<>(propertyMapping);
        valid.put("transformSteps", List.of(Map.of("op", "TRIM")));
        ApplyResult created =
                apply(proposal("PROPERTY_MAPPING"), ProposalItemKind.PROPERTY_MAPPING, valid);
        assertThat(created.targetTable()).isEqualTo("property_source_mapping");

        // 같은 표·유형의 켜진 매핑을 또 반영하면 중복이다.
        assertApplyError(
                () ->
                        apply(
                                proposal("OBJECT_MAPPING"),
                                ProposalItemKind.OBJECT_MAPPING,
                                Map.of(
                                        "objectTypeCode", type,
                                        "sourceId", sourceCode,
                                        "resourceKey", resourceKey,
                                        "keyColumns", List.of("eq_id"))),
                ApplyErrorCode.APPLY_TARGET_EXISTS,
                "payload");
    }

    @Test
    void aiProposalRecordsAiOrigin() {
        assertThat(DefinitionSource.fromProposal(ProposalOrigin.AI, 7L))
                .isEqualTo(new DefinitionSource(DefinitionOrigin.AI_PROPOSAL, 7L));
        assertThat(DefinitionSource.fromProposal(ProposalOrigin.MANUAL, 7L))
                .isEqualTo(new DefinitionSource(DefinitionOrigin.MANUAL, 7L));
        assertThatThrownBy(() -> new DefinitionSource(DefinitionOrigin.AI_PROPOSAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ApplyResult apply(long proposal, ProposalItemKind kind, Map<String, Object> payload) {
        return tx.execute(
                status ->
                        service.apply(
                                new ApplyCommand(
                                        proposal, kind, ProposalOrigin.MANUAL, payload, account)));
    }

    private static void assertApplyError(
            ThrowingCallable call, ApplyErrorCode code, String fieldPrefix) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(String.valueOf(e.getDetails().get("field")))
                                    .startsWith(fieldPrefix);
                        });
    }

    // 후보는 첫 개정본과 같은 트랜잭션에서 넣어야 한다(지연 FK fk_proposal_item_current_revision).
    private long proposal(String kind) {
        return tx.execute(
                status -> {
                    long id =
                            jdbc.sql(
                                            """
                                            insert into proposal_item (origin, item_kind, payload, created_by)
                                            values ('MANUAL', :kind, '{}'::jsonb, :actor) returning id
                                            """)
                                    .param("kind", kind)
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

    private String objectType() {
        String code = "TYPE_" + suffix().substring(0, 10).toUpperCase();
        jdbc.sql("insert into object_type (code, name) values (:code, :code)")
                .param("code", code)
                .update();
        return code;
    }

    private void column(String name, String type, int position, boolean keyPart) {
        jdbc.sql(
                        """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, is_nullable, is_key_part, ordinal_position)
                        values (:resource, :name, :type, false, :key, :position)
                        """)
                .param("resource", resourceId)
                .param("name", name)
                .param("type", type)
                .param("key", keyPart)
                .param("position", position)
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
        return UUID.randomUUID().toString().replace("-", "");
    }
}
