package com.axcore.platform.ontology.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** API 043 모델 요약. 내부 토큰으로만 열리고, 응답 형식이 명세와 같은지 본다. 범위 규칙은 ModelSummaryServiceTests. */
@SpringBootTest(properties = "axcore.internal-api.token=" + ModelSummaryApiTests.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ModelSummaryApiTests {

    static final String TOKEN = "model-summary-test-token";
    private static final String PATH = "/api/v1/internal/ontology/model-summary";
    private static final String HEADER = "X-Internal-Token";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void returnsDefinitionsInSpecShape() throws Exception {
        String typeCode = "SUMMARY_API_" + UUID.randomUUID().toString().replace("-", "");
        long typeId =
                jdbc.sql(
                                """
                                insert into object_type (code, name, description)
                                values (:code, '설비', '생산 설비') returning id
                                """)
                        .param("code", typeCode)
                        .query(Long.class)
                        .single();
        long propertyId =
                jdbc.sql(
                                """
                                insert into property_definition
                                    (object_type_id, code, name, data_type, description, sensitivity)
                                values (:type, 'code', '설비 코드', 'STRING', '원천 설비 식별자', 'NORMAL')
                                returning id
                                """)
                        .param("type", typeId)
                        .query(Long.class)
                        .single();

        mvc.perform(get(PATH).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.objectTypes[?(@.id == " + typeId + ")].code")
                                .value(hasItem(typeCode)))
                .andExpect(
                        jsonPath("$.data.objectTypes[?(@.id == " + typeId + ")].description")
                                .value(hasItem("생산 설비")))
                .andExpect(
                        jsonPath("$.data.properties[?(@.id == " + propertyId + ")].objectTypeId")
                                .value(hasItem((int) typeId)))
                .andExpect(
                        jsonPath("$.data.properties[?(@.id == " + propertyId + ")].dataType")
                                .value(hasItem("STRING")))
                .andExpect(
                        jsonPath("$.data.properties[?(@.id == " + propertyId + ")].sensitivity")
                                .value(hasItem("NORMAL")))
                .andExpect(
                        jsonPath("$.data.properties[?(@.id == " + propertyId + ")].temporalRole")
                                .value(hasItem((Object) null)))
                .andExpect(jsonPath("$.data.linkTypes").isArray())
                .andExpect(jsonPath("$.data.activeRules").isArray());
    }

    @Test
    void rejectsCallsWithoutServiceToken() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get(PATH).header(HEADER, TOKEN + "x")).andExpect(status().isUnauthorized());
    }
}
