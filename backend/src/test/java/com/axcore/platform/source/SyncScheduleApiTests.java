package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 원천별 수집 주기 확인(071)·설정(072) (S15P31C106-318). 072는 관리 Action UPDATE_SYNC_SCHEDULE이라 성공·차단을 감사 기록으로
 * 확인한다.
 */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SyncScheduleApiTests extends SourceApiTestSupport {

    private LoginSession admin;
    private long sourceId;

    @BeforeEach
    void prepare() throws Exception {
        admin = loginAs("ADMIN");
        sourceId = createPostgresSource(admin, uniqueCode());
    }

    @Test
    void returnsDefaultScheduleOfNewSource() throws Exception {
        send(admin, get(schedulePath(sourceId)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncEnabled").value(false))
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.rowVersion").value(0));
    }

    @Test
    void updatesScheduleAndRecordsActionWithChangedColumns() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(true, 15, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncEnabled").value(true))
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(15))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        assertThat(stored())
                .containsEntry("sync_enabled", true)
                .containsEntry("sync_interval_minutes", 15)
                .containsEntry("row_version", 1)
                .containsEntry("updated_by", admin.accountId());
        send(admin, get(schedulePath(sourceId)), null)
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(15))
                .andExpect(jsonPath("$.data.rowVersion").value(1));

        Map<String, Object> audit = lastAudit();
        assertThat(audit)
                .containsEntry("event_kind", "ACTION")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("entry_point", "WEB")
                .containsEntry("checked_permission_code", "SYNC_SCHEDULE_MANAGE")
                .containsEntry("target_type", "data_source")
                .containsEntry("target_id", sourceId);
        List<Map<String, Object>> changes = changesOf((Long) audit.get("id"));
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0)).containsEntry("change_kind", "UPDATE");
        assertThat((String) changes.get(0).get("before_data"))
                .contains("\"sync_enabled\": false", "\"row_version\": 0");
        assertThat((String) changes.get(0).get("after_data"))
                .contains(
                        "\"sync_enabled\": true",
                        "\"sync_interval_minutes\": 15",
                        "\"row_version\": 1");
    }

    @Test
    void disablingWithoutIntervalClearsInterval() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(true, 15, 0)).andExpect(status().isOk());

        send(admin, put(schedulePath(sourceId)), body(false, null, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncEnabled").value(false))
                .andExpect(jsonPath("$.data.syncIntervalMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.rowVersion").value(2));

        assertThat(stored())
                .containsEntry("sync_enabled", false)
                .containsEntry("sync_interval_minutes", null);
    }

    @Test
    void sameValuesKeepRowVersionAndLeaveNoChangeRow() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(false, null, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value(0));

        Map<String, Object> audit = lastAudit();
        assertThat(audit).containsEntry("status", "SUCCEEDED");
        assertThat(changesOf((Long) audit.get("id"))).isEmpty();
    }

    @Test
    void rejectsStaleRowVersionWithConflictAndBlockedRecord() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(true, 15, 0)).andExpect(status().isOk());

        send(admin, put(schedulePath(sourceId)), body(true, 30, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.expected").value(0))
                .andExpect(jsonPath("$.error.details.actual").value(1));

        assertThat(stored()).containsEntry("sync_interval_minutes", 15);
        Map<String, Object> audit = lastAudit();
        assertThat(audit)
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_type", "data_source")
                .containsEntry("target_id", sourceId);
        assertThat(changesOf((Long) audit.get("id"))).isEmpty();
    }

    @Test
    void onlyAdminCanReadOrUpdateAndBlockedUpdateIsRecorded() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        send(viewer, get(schedulePath(sourceId)), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        send(viewer, put(schedulePath(sourceId)), body(true, 15, 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.requiredPermission").value("ADMIN"));

        assertThat(stored()).containsEntry("sync_enabled", false);
        Map<String, Object> audit = lastAuditOf(viewer.accountId());
        assertThat(audit)
                .containsEntry("status", "BLOCKED")
                .containsEntry("actor_role_code", "VIEWER")
                .containsEntry("checked_permission_code", "SYNC_SCHEDULE_MANAGE");
    }

    @Test
    void rejectsEnablingWithoutIntervalAndRecordsBlocked() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(true, null, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("syncIntervalMinutes"));

        assertThat(lastAudit())
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_id", sourceId);
    }

    @Test
    void rejectsMalformedRequests() throws Exception {
        send(admin, put(schedulePath(sourceId)), body(true, 0, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        send(admin, put(schedulePath(sourceId)), "{\"syncIntervalMinutes\": 5, \"rowVersion\": 0}")
                .andExpect(status().isBadRequest());
        send(
                        admin,
                        put(schedulePath(sourceId)),
                        "{\"syncEnabled\": true, \"syncIntervalMinutes\": 5}")
                .andExpect(status().isBadRequest());

        assertThat(stored()).containsEntry("sync_enabled", false).containsEntry("row_version", 0);
    }

    @Test
    void unknownSourceIsNotFoundAndBlockedWithoutTarget() throws Exception {
        long missing = Long.MAX_VALUE;

        send(admin, get(schedulePath(missing)), null).andExpect(status().isNotFound());
        send(admin, put(schedulePath(missing)), body(true, 15, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(lastAudit())
                .containsEntry("status", "BLOCKED")
                .containsEntry("target_type", null)
                .containsEntry("target_id", null);
    }

    private static String schedulePath(long id) {
        return BASE_PATH + "/" + id + "/sync-schedule";
    }

    private static String body(boolean enabled, Integer interval, long rowVersion) {
        return """
                {"syncEnabled": %s, "syncIntervalMinutes": %s, "rowVersion": %d}
                """
                .formatted(enabled, interval, rowVersion);
    }

    private Map<String, Object> stored() {
        return jdbcClient
                .sql(
                        """
                        select sync_enabled, sync_interval_minutes, row_version, updated_by
                        from data_source where id = :id
                        """)
                .param("id", sourceId)
                .query()
                .singleRow();
    }

    private Map<String, Object> lastAudit() {
        return lastAuditOf(admin.accountId());
    }

    private Map<String, Object> lastAuditOf(long actorId) {
        return jdbcClient
                .sql(
                        """
                        select e.id, e.event_kind, e.status, e.entry_point, e.actor_role_code,
                               e.checked_permission_code, e.target_type, e.target_id, e.blocked_reason
                        from action_execution e
                        join action_definition d on d.id = e.action_definition_id
                        where d.code = 'UPDATE_SYNC_SCHEDULE' and e.actor_id = :actorId
                        order by e.id desc
                        limit 1
                        """)
                .param("actorId", actorId)
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> changesOf(Long executionId) {
        return jdbcClient
                .sql(
                        """
                        select change_kind, before_data::text as before_data, after_data::text as after_data
                        from entity_change where action_execution_id = :id order by id
                        """)
                .param("id", executionId)
                .query()
                .listOfRows();
    }
}
