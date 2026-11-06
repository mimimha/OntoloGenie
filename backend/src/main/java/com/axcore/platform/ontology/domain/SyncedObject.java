package com.axcore.platform.ontology.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 원천 행 하나를 매핑으로 바꾼 객체 값. 수집(Jira 197 전체 읽기, 198 CDC)이 매핑·변환을 마친 뒤 객체 저장(Jira 180)에 넘긴다.
 *
 * @param sourceResourceId 원천 테이블. 삭제 흔적과 세대 확인에 쓴다
 * @param mappingId 객체 원천 매핑. (mappingId, sourceKey)가 객체 하나다
 * @param objectTypeId 매핑이 만드는 객체 유형
 * @param sourceKey 원천 키(컬럼 → 값). CDC 테이블은 원천 PK와 같아야 삭제 이벤트로 객체를 찾을 수 있다
 * @param displayName 표시 이름
 * @param properties 속성 코드 → 값
 */
public record SyncedObject(
        long sourceResourceId,
        long mappingId,
        long objectTypeId,
        Map<String, Object> sourceKey,
        String displayName,
        Map<String, Object> properties) {

    public SyncedObject {
        if (sourceResourceId < 1 || mappingId < 1 || objectTypeId < 1) {
            throw new IllegalArgumentException("원천 테이블·매핑·객체 유형 ID는 1 이상입니다.");
        }
        Objects.requireNonNull(sourceKey, "sourceKey");
        if (sourceKey.isEmpty()) {
            throw new IllegalArgumentException("원천 키가 비어 있습니다.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("표시 이름이 비어 있습니다.");
        }
        sourceKey = Collections.unmodifiableMap(new LinkedHashMap<>(sourceKey));
        properties =
                properties == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
