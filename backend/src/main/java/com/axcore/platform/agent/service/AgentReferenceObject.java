package com.axcore.platform.agent.service;

import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 답변이 대상으로 삼은 객체(147). 질문의 대상을 서버가 객체 검색으로 찾았거나 화면이 참고 객체로 보낸 것이다. 화면은 ID만 보내므로 그때는 이름·유형이 null이다.
 * answerMeta.conditions.referenceObjects로 저장해 다음 질문이 이어 쓴다.
 */
public record AgentReferenceObject(long id, String name, String typeName) {

    static AgentReferenceObject idOnly(long id) {
        return new AgentReferenceObject(id, null, null);
    }

    static AgentReferenceObject from(ObjectSearchResponse found) {
        return new AgentReferenceObject(found.id(), found.displayName(), found.objectTypeName());
    }

    /** answerMeta에 저장한 값({@link #toMap()})을 다시 읽는다. id가 없으면 null이다. */
    static AgentReferenceObject fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("id") instanceof Number id)) {
            return null;
        }
        return new AgentReferenceObject(
                id.longValue(),
                map.get("name") instanceof String name ? name : null,
                map.get("typeName") instanceof String typeName ? typeName : null);
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("typeName", typeName);
        return map;
    }

    /** AI에게 알려 줄 때 쓰는 짧은 설명. 예: 301 프레스 3호기(설비) */
    String describe() {
        if (name == null) {
            return String.valueOf(id);
        }
        return typeName == null ? id + " " + name : id + " " + name + "(" + typeName + ")";
    }
}
