package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

class AgentTargetResolverTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneOffset.UTC);

    private final ObjectSearchQuery objects = mock(ObjectSearchQuery.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session = new AgentQuerySession(ASKER, 11L, recorder, CLOCK);
    private final AgentTargetResolver resolver = new AgentTargetResolver(objects);

    @Test
    void picksObjectWithSameNameAmongSimilarOnes() {
        searchReturns("PR-03", object(301L, "PR-03"), object(331L, "PR-03A"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-03"), List.of(), session);

        assertThat(resolution.found())
                .containsExactly(new AgentReferenceObject(301L, "PR-03", "설비"));
        assertThat(resolution.issues()).isEmpty();
    }

    @Test
    void picksIdentifierPropertyWithSameValue() {
        searchReturns("PR-03", object(301L, "프레스 3호기", Map.of("code", "PR-03")));

        assertThat(resolver.resolve(List.of("PR-03"), List.of(), session).found())
                .extracting(AgentReferenceObject::id)
                .containsExactly(301L);
    }

    @Test
    void prefersObjectAlreadyInConversationAmongCandidates() {
        searchReturns("3호기", object(301L, "프레스 3호기"), object(309L, "조립 3호기"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(
                        List.of("3호기"), List.of(AgentReferenceObject.idOnly(301L)), session);

        assertThat(resolution.found()).extracting(AgentReferenceObject::id).containsExactly(301L);
    }

    @Test
    void reusesKnownObjectWithSameNameWithoutSearching() {
        AgentReferenceObject pr03 = new AgentReferenceObject(301L, "PR-03", "설비");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("pr-03"), List.of(pr03), session);

        assertThat(resolution.found()).containsExactly(pr03);
        verifyNoInteractions(objects, recorder);
    }

    @Test
    void asksAgainInsteadOfPickingFirstWhenShownCandidatesShareName() {
        AgentReferenceObject first = new AgentReferenceObject(306L, "PR-03", "설비");
        AgentReferenceObject second = new AgentReferenceObject(406L, "PR-03", "설비");
        searchReturns("PR-03", object(306L, "PR-03"), object(406L, "PR-03"));

        // 이름이 같은 두 후보를 되물었는데 사용자가 같은 이름을 다시 말한 경우
        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-03"), List.of(first, second), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues().getFirst().candidates()).containsExactly(first, second);
    }

    @Test
    void picksShownCandidateByNumber() {
        AgentReferenceObject first = new AgentReferenceObject(306L, "PR-03", "설비");
        AgentReferenceObject second = new AgentReferenceObject(406L, "PR-03", "설비");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("번호 406"), List.of(first, second), session);

        assertThat(resolution.found()).containsExactly(second);
        verifyNoInteractions(objects);
    }

    @Test
    void returnsCandidatesWhenSeveralObjectsMatch() {
        searchReturns("06번");
        searchReturns("06", object(306L, "PR-06"), object(406L, "CNC-06"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(
                        List.of("06번"), List.of(AgentReferenceObject.idOnly(301L)), session);

        assertThat(resolution.found()).isEmpty();
        assertThat(resolution.issues())
                .containsExactly(
                        new AgentTargetResolver.TargetIssue(
                                "06번",
                                List.of(
                                        new AgentReferenceObject(306L, "PR-06", "설비"),
                                        new AgentReferenceObject(406L, "CNC-06", "설비"))));
    }

    @Test
    void reportsTargetThatMatchesNothing() {
        searchReturns("99번");
        searchReturns("99");

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("99번"), List.of(), session);

        assertThat(resolution.issues())
                .containsExactly(new AgentTargetResolver.TargetIssue("99번", List.of()));
    }

    @Test
    void showsObjectsWithSameNameFirstWhenSeveralShareIt() {
        searchReturns(
                "PR-06",
                object(201L, "PR-06A"),
                object(202L, "PR-06B"),
                object(203L, "PR-06C"),
                object(204L, "PR-06D"),
                object(205L, "PR-06E"),
                object(306L, "PR-06"),
                object(406L, "PR-06"));

        AgentTargetResolver.Resolution resolution =
                resolver.resolve(List.of("PR-06"), List.of(), session);

        // 이름이 똑같은 두 객체가 비슷한 이름 뒤에 있어도 후보에서 빠지지 않는다.
        assertThat(resolution.issues().getFirst().candidates())
                .extracting(AgentReferenceObject::id)
                .containsExactly(306L, 406L);
    }

    @Test
    void recordsEachSearchWithoutAddingToAnswerResults() {
        searchReturns("06번");
        searchReturns("06", object(306L, "PR-06"));

        resolver.resolve(List.of("06번"), List.of(), session);

        // 검색할 때마다 질문한 사용자의 권한으로 부르고 기록하지만, 사용자가 물은 정보가 아니므로 정보별 결과에는 넣지 않는다.
        ArgumentCaptor<AgentQueryResult> recorded = ArgumentCaptor.forClass(AgentQueryResult.class);
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        eq(Map.of("q", "06번")),
                        isNull(),
                        isNull(),
                        recorded.capture(),
                        any());
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(2),
                        eq(Map.of("q", "06")),
                        isNull(),
                        isNull(),
                        recorded.capture(),
                        any());
        assertThat(recorded.getAllValues())
                .extracting(AgentQueryResult::toolCode, AgentQueryResult::resultCategory)
                .containsExactly(
                        tuple(AgentTargetResolver.TARGET_SEARCH, AgentResultCategory.NO_DATA),
                        tuple(AgentTargetResolver.TARGET_SEARCH, AgentResultCategory.FOUND));
        assertThat(session.results()).isEmpty();
    }

    private void searchReturns(String query, ObjectSearchResponse... rows) {
        when(objects.search(eq(ASKER), eq(new ObjectSearchRequest(query, null, null, 0, 100))))
                .thenReturn(new PageImpl<>(List.of(rows)));
    }

    private static ObjectSearchResponse object(long id, String name) {
        return object(id, name, Map.of());
    }

    private static ObjectSearchResponse object(
            long id, String name, Map<String, Object> properties) {
        return new ObjectSearchResponse(
                id,
                1L,
                "설비",
                name,
                properties,
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                null,
                Map.of(),
                null,
                null,
                null);
    }
}
