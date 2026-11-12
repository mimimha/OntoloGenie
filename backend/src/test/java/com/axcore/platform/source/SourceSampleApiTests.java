package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.audit.domain.EventKind;
import com.axcore.platform.audit.service.AuditRecorder;
import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourceSampleApiTests extends SourceApiTestSupport {

    private static final String RESULT_PATH = "/api/v1/ontology/source-previews/{previewId}";

    @MockitoSpyBean AuditRecorder audits;

    @Test
    void joinsConcurrentRequestsAndReadsTheQueuedJobThroughApi102() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long resourceId = insertResource(sourceId, "erp.employee", "TABLE", true);
        String path = samples(sourceId, resourceId);

        var first = CompletableFuture.supplyAsync(() -> accept(admin, path, null));
        var second = CompletableFuture.supplyAsync(() -> accept(admin, path, "{\"rowLimit\":20}"));
        Accepted firstResult = first.join();
        Accepted secondResult = second.join();
        assertThat(firstResult.id()).isEqualTo(secondResult.id());
        assertThat(firstResult.joined()).isNotEqualTo(secondResult.joined());
        assertThat(accept(admin, path, "{\"rowLimit\":1}").id()).isNotEqualTo(firstResult.id());

        mockMvc.perform(get(RESULT_PATH, firstResult.id()).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewKind").value("SAMPLE"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.sourceResourceId").value(resourceId));
        assertThat(
                        jdbcClient
                                .sql(
                                        "select count(*) from source_preview where source_resource_id = :id")
                                .param("id", resourceId)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void rejectsInvalidTargetsLimitsAndPermissions() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        LoginSession viewer = loginAs("VIEWER");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long otherSourceId = createPostgresSource(admin, uniqueCode());
        long resourceId = insertResource(sourceId, "erp.employee", "TABLE", true);
        long unreadable = insertResource(sourceId, "erp.restricted", "TABLE", false);
        long view = insertResource(sourceId, "erp.employee_view", "VIEW", true);
        String path = samples(sourceId, resourceId);

        for (String body : new String[] {"{\"rowLimit\":0}", "{\"rowLimit\":21}"}) {
            send(admin, post(path), body).andExpect(status().isBadRequest());
        }
        send(admin, post(samples(otherSourceId, resourceId)), null)
                .andExpect(status().isNotFound());
        send(admin, post(samples(sourceId, unreadable)), null)
                .andExpect(status().isUnprocessableContent());
        send(admin, post(samples(sourceId, view)), null)
                .andExpect(status().isUnprocessableContent());
        send(viewer, post(path), null).andExpect(status().isForbidden());
        mockMvc.perform(post(path).cookie(admin.session())).andExpect(status().isForbidden());
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                select count(*) from source_preview
                where source_resource_id in (:resourceId, :unreadable, :view)
                """)
                                .param("resourceId", resourceId)
                                .param("unreadable", unreadable)
                                .param("view", view)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void rollsBackTheJobIfTheAuditCannotBeSaved() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long resourceId = insertResource(sourceId, "erp.employee", "TABLE", true);
        doThrow(new IllegalStateException("test audit failure"))
                .when(audits)
                .record(argThat(entry -> entry.eventKind() == EventKind.SOURCE_PREVIEW));

        send(admin, post(samples(sourceId, resourceId)), null)
                .andExpect(status().isInternalServerError());

        assertThat(
                        jdbcClient
                                .sql(
                                        "select count(*) from source_preview where source_resource_id = :id")
                                .param("id", resourceId)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void returnsSampleColumnsAndRowsThroughApi102() throws Exception {
        LoginSession admin = loginAs("ADMIN");
        long sourceId = createPostgresSource(admin, uniqueCode());
        long resourceId = insertResource(sourceId, "erp.employee", "TABLE", true);
        long previewId = accept(admin, samples(sourceId, resourceId), null).id();
        jdbcClient
                .sql(
                        """
                update source_preview set status = 'SUCCEEDED', started_at = now(), finished_at = now(),
                    result = '{"columns":["emp_no","label"],"rows":[["E001",null]]}'::jsonb
                where id = :id
                """)
                .param("id", previewId)
                .update();

        mockMvc.perform(get(RESULT_PATH, previewId).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewKind").value("SAMPLE"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.columns[0]").value("emp_no"))
                .andExpect(jsonPath("$.data.result.columns[1]").value("label"))
                .andExpect(jsonPath("$.data.result.rows[0][0]").value("E001"))
                .andExpect(jsonPath("$.data.result.rows[0][1]").value(nullValue()));
    }

    private Accepted accept(LoginSession admin, String path, String body) {
        try {
            String response =
                    send(admin, post(path), body)
                            .andExpect(status().isAccepted())
                            .andExpect(jsonPath("$.data.status").value("QUEUED"))
                            .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Number id = JsonPath.read(response, "$.data.previewId");
            return new Accepted(id.longValue(), JsonPath.read(response, "$.data.joined"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private long insertResource(long sourceId, String key, String kind, boolean readable) {
        return jdbcClient
                .sql(
                        """
                insert into source_resource (data_source_id, resource_key, resource_kind, is_readable)
                values (:sourceId, :key, :kind, :readable)
                returning id
                """)
                .param("sourceId", sourceId)
                .param("key", key)
                .param("kind", kind)
                .param("readable", readable)
                .query(Long.class)
                .single();
    }

    private static String samples(long sourceId, long resourceId) {
        return BASE_PATH + "/" + sourceId + "/resources/" + resourceId + "/samples";
    }

    private record Accepted(long id, boolean joined) {}
}
