package com.axcore.platform.source.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 안의 표·뷰·문서 집합 (FN-SRC-02). 연결 검사(배치)가 채우고 api는 읽기만 한다. 매핑(object_source_mapping)과 수집 기록이 이 행을
 * 가리킨다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long dataSourceId;

    /** 원천 안에서의 이름. 표·뷰는 {스키마}.{이름}. */
    private String resourceKey;

    @Enumerated(EnumType.STRING)
    private ResourceKind resourceKind;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sourceMetadata;

    /** SYNC 계정 기준 읽기 가능 여부. AI 계정 기준은 저장하지 않는다. */
    private boolean isReadable;

    @Enumerated(EnumType.STRING)
    private SyncStrategy syncStrategy;

    /**
     * CDC 테이블을 마지막으로 전체 읽기한 시점의 원천 LSN(참고값). NULL이면 다시 전체 읽기가 필요하다. CDC 이벤트는 이 값과 무관하게 항상
     * 반영한다(V11). 전체 읽기와 이벤트의 경계는 객체의 적재 스냅샷으로 판단한다.
     */
    private Long loadedLsn;

    private Instant lastSeenAt;

    /**
     * 매핑이 바뀌어 이 테이블을 처음부터 다시 읽어야 한다. CDC 테이블은 LSN을 비워 다시 읽기를 요청한다. 그동안에도 CDC 이벤트는 계속 반영한다(V11).
     * FULL_COMPARE 테이블은 원래 매번 전체를 읽으므로 바꿀 것이 없다.
     */
    public void requireFullReload() {
        this.loadedLsn = null;
    }
}
