package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AgentObjectDetailToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final ObjectDetailQuery objects = mock(ObjectDetailQuery.class);
    private final AgentQueryRecorder recorder = mock(AgentQueryRecorder.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, recorder, Clock.systemUTC());
    private final AgentObjectDetailTools tools = new AgentObjectDetailTools(session, objects);

    @Test
    void returnsDetailAsFound() {
        ObjectDetailResponse detail = detail(301L);
        when(objects.detail(ASKER, 301L)).thenReturn(detail);

        Map<String, Object> reply = tools.objectDetail(301L);

        assertThat(reply)
                .containsEntry("resultCategory", "FOUND")
                .containsEntry("data", AgentObjectDetailTools.ObjectDetail.from(detail));
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentObjectDetailTools.OBJECT_DETAIL);
        assertThat(result.sources()).containsExactly(Map.of("objectId", 301L));
        assertThat(result.dataAsOf()).isNull();
    }

    @Test
    void treatsHiddenOrMissingObjectAsNoData() {
        when(objects.detail(ASKER, 999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, Map.of()));

        assertThat(tools.objectDetail(999L)).containsEntry("resultCategory", "NO_DATA");
        assertThat(session.results().getFirst().sources()).isEmpty();
    }

    @Test
    void listsLinksWithBaseAndRelatedObjectsAsSources() {
        when(objects.links(eq(ASKER), eq(301L), any()))
                .thenReturn(
                        new PageImpl<>(
                                List.of(link(91L, 501L), link(92L, 502L)),
                                PageRequest.of(0, 20),
                                2));

        Map<String, Object> reply = tools.objectLinks(301L, 12L, "in");

        ArgumentCaptor<ObjectLinkSearchRequest> request =
                ArgumentCaptor.forClass(ObjectLinkSearchRequest.class);
        verify(objects).links(eq(ASKER), eq(301L), request.capture());
        assertThat(request.getValue())
                .isEqualTo(
                        new ObjectLinkSearchRequest(
                                12L,
                                ObjectLinkSearchRequest.Direction.IN,
                                0,
                                AgentQueryTools.MAX_ROWS));
        assertThat(reply).containsEntry("resultCategory", "FOUND");
        assertThat(((Map<?, ?>) reply.get("data")).get("total")).isEqualTo(2L);
        assertThat(session.results().getFirst().sources())
                .containsExactly(
                        Map.of("objectId", 301L),
                        Map.of("objectId", 501L),
                        Map.of("objectId", 502L));
    }

    /** AI가 잘못된 값을 보내도 메서드가 불려 조회 기록이 남는다(Spring AI가 인자를 바꾸다 실패하면 기록 없이 끝난다). */
    @Test
    void recordsCallWithUnknownDirectionAsFailed() {
        String reply =
                toolCallback(AgentObjectDetailTools.OBJECT_LINKS)
                        .call("{\"objectId\": 301, \"direction\": \"OUTGOING\"}");

        assertThat(reply).contains("error");
        assertThat(session.results().getFirst().succeeded()).isFalse();
        verify(recorder)
                .record(
                        eq(ASKER),
                        eq(11L),
                        eq(1),
                        any(),
                        eq("object_instance"),
                        eq(301L),
                        any(),
                        any());
        verifyNoInteractions(objects);
    }

    @Test
    void recordsCallWithoutObjectIdAsFailedWithoutTarget() {
        String reply = toolCallback(AgentObjectDetailTools.OBJECT_DETAIL).call("{}");

        assertThat(reply).contains("error");
        assertThat(session.results().getFirst().succeeded()).isFalse();
        verify(recorder).record(eq(ASKER), eq(11L), eq(1), any(), isNull(), isNull(), any(), any());
        verifyNoInteractions(objects);
    }

    @Test
    void answersObjectWithoutVisibleLinksAsUnlinked() {
        when(objects.links(eq(ASKER), eq(301L), any())).thenReturn(new PageImpl<>(List.of()));

        assertThat(tools.objectLinks(301L, null, null)).containsEntry("resultCategory", "UNLINKED");
        assertThat(session.results().getFirst().resultCategory())
                .isEqualTo(AgentResultCategory.UNLINKED);
    }

    @Test
    void answersLinksOfMissingObjectAsNoData() {
        when(objects.links(eq(ASKER), eq(999L), any()))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, Map.of()));

        assertThat(tools.objectLinks(999L, null, null)).containsEntry("resultCategory", "NO_DATA");
    }

    private ToolCallback toolCallback(String name) {
        return Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectDetailResponse detail(long id) {
        return new ObjectDetailResponse(
                id,
                4L,
                "설비",
                "PR-03",
                Map.of("equipment_code", "PR-03"),
                Map.of("equipment_code", "설비 코드"),
                List.of(),
                ObjectSearchRequest.Status.ACTIVE,
                8L,
                "MES",
                "mes.equipment",
                Map.of("equipment_code", "PR-03"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:00Z"),
                null);
    }

    private static ObjectLinkResponse link(long id, long relatedId) {
        return new ObjectLinkResponse(
                id,
                12L,
                "현재 배정 설비",
                relatedId,
                301L,
                new ObjectLinkResponse.RelatedObject(relatedId, "WO-" + relatedId, 5L, "작업지시"),
                ObjectLinkResponse.Origin.RULE,
                "ACTIVE",
                3L,
                null,
                ObjectLinkResponse.ExpressionKind.OBSERVED,
                null);
    }
}
