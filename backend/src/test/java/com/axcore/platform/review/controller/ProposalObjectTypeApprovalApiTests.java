package com.axcore.platform.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.review.DecisionHistoryFixtures;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "test.context=csrf-api")
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ProposalObjectTypeApprovalApiTests {
    private static final String PROPOSAL = "/api/v1/proposals/{id}";
    private static final String AUDIT = "/api/v1/audit/actions";
    private static final String KEY_COLUMN = "equipment_id";

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final DecisionHistoryFixtures fixtures;
    private long actor;
    private long proposal;
    private long resource;
    private String sourceCode;
    private String resourceKey;
    private String typeCode;

    @Autowired
    ProposalObjectTypeApprovalApiTests(MockMvc mvc, JdbcClient jdbc, JsonMapper json) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.fixtures = new DecisionHistoryFixtures(jdbc);
    }

    @BeforeEach
    void prepareSource() {
        actor = fixtures.account();
        String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        sourceCode = "ERP_" + suffix;
        typeCode = "EQUIPMENT_" + suffix;
        resourceKey = "erp.equipment";
        long source =
                jdbc.sql(
                                """
                                insert into data_source(code, name, source_kind)
                                values (:code, '승인 검증 원천', 'POSTGRES') returning id
                                """)
                        .param("code", sourceCode)
                        .query(Long.class)
                        .single();
        resource =
                jdbc.sql(
                                """
                                insert into source_resource(data_source_id, resource_key, resource_kind, sync_strategy, loaded_lsn)
                                values (:source, :key, 'TABLE', 'CDC', 100) returning id
                                """)
                        .param("source", source)
                        .param("key", resourceKey)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into source_column(source_resource_id, column_name, source_data_type, is_nullable, is_key_part, ordinal_position)
                        values (:resource, :column, 'varchar', false, true, 1)
                        """)
                .param("resource", resource)
                .param("column", KEY_COLUMN)
                .update();
    }

    @Test
    void approvalWithoutACandidateCheckCommitsTheObjectTypeAndLinksTheResultToItsDecisionAndAudit()
            throws Exception {
        prepareCandidate(KEY_COLUMN);

        JsonNode approved =
                body(approve()
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                                .andExpect(jsonPath("$.data.applyStatus").value("APPLIED")))
                        .path("data");
        long type = approved.at("/appliedEntityIds/0").asLong();
        long decision = approved.path("decisionId").asLong();
        long execution = approved.path("actionExecutionId").asLong();
        assertThat(
                        jdbc.sql(
                                        """
                                        select t.id from object_type t
                                        join object_source_mapping m on m.object_type_id = t.id
                                        join property_source_mapping p on p.source_mapping_id = m.id
                                        join source_resource r on r.id = m.source_resource_id
                                        where t.proposal_item_id = :proposal and t.code = :code
                                          and r.id = :resource and r.loaded_lsn is null
                                        """)
                                .param("proposal", proposal)
                                .param("code", typeCode)
                                .param("resource", resource)
                                .query(Long.class)
                                .single())
                .isEqualTo(type);

        read(PROPOSAL, proposal)
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.applyStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.appliedAt").isNotEmpty());
        read(PROPOSAL + "/decisions", proposal)
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(decision))
                .andExpect(jsonPath("$.data[0].itemRevision").value(1))
                .andExpect(jsonPath("$.data[0].decidedBy").value(actor))
                .andExpect(jsonPath("$.data[0].decision").value("APPROVE"));
        readCandidateAudits()
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(execution))
                .andExpect(jsonPath("$.data[0].actionCode").value("DECIDE_PROPOSAL"));
        read(AUDIT + "/{id}", execution)
                .andExpect(jsonPath("$.data.targetType").value("proposal_item"))
                .andExpect(jsonPath("$.data.targetId").value(proposal))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.input.proposalItemId").value(proposal))
                .andExpect(jsonPath("$.data.input.revision").value(1))
                .andExpect(jsonPath("$.data.result.basisCheckRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.result.decisionId").value(decision))
                .andExpect(jsonPath("$.data.result.targetTable").value("object_type"))
                .andExpect(jsonPath("$.data.result.targetId").value(type))
                .andExpect(jsonPath("$.data.result.reloadResourceIds[0]").value(resource))
                .andExpect(
                        jsonPath(
                                "$.data.entityChanges[?(@.tableName == 'object_type')].afterData.code",
                                contains(typeCode)));
    }

    @Test
    void missingSourceColumnRollsBackTheBundleAndApprovalButPreservesTheFailureAudit()
            throws Exception {
        // 속성 매핑에서 실패하도록 해 앞서 저장한 유형·속성·객체 매핑도 롤백되는지 본다.
        prepareCandidate("missing_column");

        long execution =
                body(approve()
                                .andExpect(status().isUnprocessableContent())
                                .andExpect(jsonPath("$.error.code").value("APPLY_FAILED")))
                        .at("/error/details/actionExecutionId")
                        .asLong();

        assertThat(
                        jdbc.sql(
                                        """
                                        select
                                            (select count(*) from object_type where proposal_item_id = :proposal) as types,
                                            (select count(*) from property_definition where proposal_item_id = :proposal) as properties,
                                            (select count(*) from object_source_mapping where source_resource_id = :resource) as mappings,
                                            loaded_lsn
                                        from source_resource where id = :resource
                                        """)
                                .param("proposal", proposal)
                                .param("resource", resource)
                                .query()
                                .singleRow())
                .containsEntry("types", 0L)
                .containsEntry("properties", 0L)
                .containsEntry("mappings", 0L)
                .containsEntry("loaded_lsn", 100L);
        read(PROPOSAL, proposal)
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.applyStatus").value("NOT_APPLIED"))
                .andExpect(jsonPath("$.data.appliedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.applyError").value(nullValue()));
        read(PROPOSAL + "/decisions", proposal)
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        readCandidateAudits()
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(execution))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"));
        read(AUDIT + "/{id}", execution)
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.input.proposalItemId").value(proposal))
                .andExpect(jsonPath("$.data.input.revision").value(1))
                .andExpect(jsonPath("$.data.input.decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.result.basisCheckRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.result.errorCode").value("APPLY_REFERENCE_MISSING"))
                .andExpect(jsonPath("$.data.result.decisionId").doesNotExist())
                .andExpect(jsonPath("$.data.result.targetId").doesNotExist())
                .andExpect(jsonPath("$.data.entityChanges").isEmpty());
    }

    private void prepareCandidate(String propertyColumn) {
        var mapping =
                Map.of(
                        "sourceId",
                        sourceCode,
                        "resourceKey",
                        resourceKey,
                        "keyColumns",
                        List.of(KEY_COLUMN));
        var property =
                Map.of(
                        "code", "equipment_id",
                        "name", "설비 번호",
                        "dataType", "STRING",
                        "sourceColumn",
                                Map.of(
                                        "sourceId", sourceCode,
                                        "resourceKey", resourceKey,
                                        "column", propertyColumn));
        var payload =
                Map.of(
                        "code",
                        typeCode,
                        "name",
                        "설비",
                        "objectMapping",
                        mapping,
                        "properties",
                        List.of(property));
        proposal =
                fixtures.proposal(
                        actor, ProposalItemKind.OBJECT_TYPE, json.writeValueAsString(payload));
        // 객체 유형은 후보 검사 대상이 아니라(CandidateCheckTarget) 검사 없이 승인한다.
    }

    private ResultActions approve() throws Exception {
        return mvc.perform(
                post(PROPOSAL + "/decisions", proposal)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "revision",
                                                1,
                                                "decision",
                                                "APPROVE",
                                                "reason",
                                                "검사 결과 확인"))));
    }

    private ResultActions read(String path, Object... variables) throws Exception {
        return mvc.perform(get(path, variables).with(admin())).andExpect(status().isOk());
    }

    private ResultActions readCandidateAudits() throws Exception {
        return read(
                AUDIT + "?actionCode=DECIDE_PROPOSAL&targetType=proposal_item&targetId={id}",
                proposal);
    }

    private RequestPostProcessor admin() {
        return authentication(
                new LoginAccount(actor, "reviewer", "ADMIN", false).toAuthentication());
    }

    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
}
