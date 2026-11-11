package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;

/**
 * 객체 상세(077)와 활성 관계(078) 조회 도구(326). {@link ObjectDetailQuery} 구현(S15P31C106-137)이 있을 때만 AI에게 준다.
 * 권한·민감 속성 처리는 조회 서비스가 하고, 여기서는 질문한 사용자로 부르기만 한다. 기준 시점(테이블 확인 시각)은 S15P31C106-145에서 채운다.
 */
public class AgentObjectDetailTools {
    static final String OBJECT_DETAIL = "OBJECT_DETAIL";
    static final String OBJECT_LINKS = "OBJECT_LINKS";

    private final AgentQuerySession session;
    private final ObjectDetailQuery objects;

    public AgentObjectDetailTools(AgentQuerySession session, ObjectDetailQuery objects) {
        this.session = session;
        this.objects = objects;
    }

    @Tool(name = OBJECT_DETAIL, description = "객체 하나의 속성, 원천 위치, 수집 시각을 조회한다. 상대 객체의 속성도 이 도구로 본다.")
    public Map<String, Object> objectDetail(@ToolParam(description = "객체 ID") Long objectId) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectId", objectId);
        return session.run(
                OBJECT_DETAIL,
                "객체 상세",
                input,
                AgentQueryTools.targetTypeOf(objectId),
                objectId,
                () ->
                        AgentQueryTools.noDataIfMissing(
                                () -> {
                                    long id = AgentQueryTools.required(objectId, "objectId");
                                    ObjectDetailResponse detail =
                                            objects.detail(session.asker(), id);
                                    return new AgentQuerySession.Outcome(
                                            detail,
                                            AgentResultCategory.FOUND,
                                            List.of(Map.of("objectId", id)),
                                            null);
                                }));
    }

    /** 객체는 있지만 볼 수 있는 활성 관계가 없으면 UNLINKED다(Action 정의서 8-1). 객체가 없거나 볼 수 없으면 NO_DATA다. */
    @Tool(
            name = OBJECT_LINKS,
            description =
                    "객체에 연결된 확정 관계를 최대 20건 조회한다. 연결 상대 객체의 ID·이름과 관계 근거를 돌려준다. 상대 객체의 속성은 OBJECT_DETAIL로 본다.")
    public Map<String, Object> objectLinks(
            @ToolParam(description = "기준 객체 ID") Long objectId,
            @ToolParam(
                            description = "관계 유형 ID. 사용자가 관계 종류를 말했을 때만 모델 요약의 id로 채우고, 아니면 비운다",
                            required = false)
                    Long linkTypeId,
            @ToolParam(
                            description = "OUT(기준 객체에서 나가는 관계), IN(들어오는 관계), BOTH(기본, 양쪽)",
                            required = false)
                    String direction) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectId", objectId);
        AgentQueryTools.putIfPresent(input, "linkTypeId", linkTypeId);
        AgentQueryTools.putIfPresent(input, "direction", direction);
        return session.run(
                OBJECT_LINKS,
                "관계",
                input,
                AgentQueryTools.targetTypeOf(objectId),
                objectId,
                () ->
                        AgentQueryTools.noDataIfMissing(
                                () -> {
                                    long id = AgentQueryTools.required(objectId, "objectId");
                                    Page<ObjectLinkResponse> page =
                                            objects.links(
                                                    session.asker(),
                                                    id,
                                                    new ObjectLinkSearchRequest(
                                                            linkTypeId,
                                                            AgentQueryTools.enumOf(
                                                                    ObjectLinkSearchRequest
                                                                            .Direction.class,
                                                                    direction),
                                                            0,
                                                            AgentQueryTools.MAX_ROWS));
                                    Map<String, Object> data = new LinkedHashMap<>();
                                    data.put("total", page.getTotalElements());
                                    data.put("links", page.getContent());
                                    return new AgentQuerySession.Outcome(
                                            data,
                                            page.isEmpty()
                                                    ? AgentResultCategory.UNLINKED
                                                    : AgentResultCategory.FOUND,
                                            sources(id, page.getContent()),
                                            null);
                                }));
    }

    /** 기준 객체와 연결 상대 객체. 화면이 답변에서 각 객체를 열 수 있게 한다. */
    private static List<Map<String, Object>> sources(
            long objectId, List<ObjectLinkResponse> links) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(objectId);
        links.stream()
                .map(ObjectLinkResponse::relatedObject)
                .filter(Objects::nonNull)
                .forEach(related -> ids.add(related.id()));
        return ids.stream().map(id -> Map.<String, Object>of("objectId", id)).toList();
    }
}
