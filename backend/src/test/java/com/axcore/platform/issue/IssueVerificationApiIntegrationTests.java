package com.axcore.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.domain.IssueReverification;
import com.axcore.platform.check.domain.IssueReverification.Verdict;
import com.axcore.platform.check.service.IssueReverificationRecorder;
import com.axcore.platform.global.security.LoginAccount;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class IssueVerificationApiIntegrationTests {

    private static final String PATH = "/api/v1/issues/{id}/verifications";
    private static final Instant FIXED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CHECKED_AT = FIXED_AT.plusSeconds(120);

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired IssueReverificationRecorder recorder;

    private IssueTestFixtures fixtures;
    private long accountId;
    private long baselineId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixtures = new IssueTestFixtures(jdbc);
        accountId = fixtures.insertAdmin();
        baselineId = fixtures.insertCandidateRun();
        mappingId = fixtures.insertLinkMapping();
    }

    @Test
    void returnsPassWithoutAutomaticallyResolvingIssue() throws Exception {
        long issueId = newIssue();
        fixtures.markFixedAt(issueId, accountId, FIXED_AT);
        long runId = newRun();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rawValue", null);
        detail.put("transformedValue", "E023");
        detail.put("targetFound", true);
        detail.put("classification", "MATCHED");

        record(runId, new IssueReverification(issueId, mappingId, Verdict.PASSED, detail, null));

        assertThat(issueStatus(issueId)).isEqualTo("FIXED_PENDING_REVERIFY");
        mvc.perform(adminHistory(issueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].issueId").value(issueId))
                .andExpect(jsonPath("$.data[0].checkRunId").value(runId))
                .andExpect(jsonPath("$.data[0].triggerKind").value("REVERIFY"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].outcome").value("PASSED"))
                .andExpect(jsonPath("$.data[0].detail.rawValue").hasJsonPath())
                .andExpect(jsonPath("$.data[0].detail.transformedValue").value("E023"))
                .andExpect(jsonPath("$.data[0].detail.targetFound").value(true))
                .andExpect(jsonPath("$.data[0].detail.linkMappingId").value(mappingId))
                .andExpect(jsonPath("$.data[0].checkedAt").value(CHECKED_AT.toString()));
    }

    @Test
    void reopensOnceForFailedReverification() {
        long issueId = newIssue();
        fixtures.markFixedAt(issueId, accountId, FIXED_AT);
        long runId = newRun();
        IssueReverification verdict =
                new IssueReverification(
                        issueId, mappingId, Verdict.FAILED, Map.of("targetFound", false), null);

        record(runId, verdict);
        record(runId, verdict);

        assertThat(issueStatus(issueId)).isEqualTo("CHECKING");
        assertThat(count("select count(*) from issue_verification where issue_id = :id", issueId))
                .isEqualTo(1);
        assertThat(count("select count(*) from issue_history where issue_id = :id", issueId))
                .isEqualTo(2);
    }

    @Test
    void returnsEmptyHistoryForUnevaluatedIssue() throws Exception {
        long issueId = newIssue();
        record(
                newRun(),
                new IssueReverification(
                        issueId, mappingId, Verdict.NOT_EVALUATED, Map.of(), "대상 없음"));

        mvc.perform(adminHistory(issueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void paginatesOnlyTheRequestedIssueWithStableOrder() throws Exception {
        long issueId = newIssue();
        long first = newRun();
        long second = newRun();
        IssueReverification verdict =
                new IssueReverification(issueId, mappingId, Verdict.PASSED, Map.of(), null);
        record(first, verdict);
        record(second, verdict);
        fixtures.insertVerification(newIssue(), second, "FAILED");

        mvc.perform(adminHistory(issueId).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].checkRunId").value(second))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
        mvc.perform(adminHistory(issueId).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].checkRunId").value(first))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void validatesAccessAndLookup() throws Exception {
        long issueId = newIssue();
        mvc.perform(get(PATH, issueId)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH, issueId).with(loggedIn("USER"))).andExpect(status().isForbidden());
        mvc.perform(adminHistory(Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(adminHistory(0)).andExpect(status().isBadRequest());
        mvc.perform(adminHistory(issueId).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(adminHistory(issueId).param("size", "0")).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder adminHistory(long issueId) {
        return get(PATH, issueId).with(loggedIn("ADMIN"));
    }

    private void record(long runId, IssueReverification verdict) {
        recorder.record(runId, List.of(verdict), CHECKED_AT);
    }

    private long newIssue() {
        return fixtures.insertIssue(baselineId, mappingId, "UNMATCHED", "NEW", "{}");
    }

    private long newRun() {
        return fixtures.insertRun(
                "REVERIFY", "SUCCEEDED", FIXED_AT.plusSeconds(60), baselineId, accountId);
    }

    private String issueStatus(long issueId) {
        return jdbc.sql("select status from issue where id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
    }

    private long count(String sql, long issueId) {
        return jdbc.sql(sql).param("id", issueId).query(Long.class).single();
    }

    private RequestPostProcessor loggedIn(String roleCode) {
        LoginAccount account = new LoginAccount(accountId, "issue-test", roleCode, false);
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        account, null, List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }
}
