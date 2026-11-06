package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceColumn;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceColumnRepository extends JpaRepository<SourceColumn, Long> {

    /** 원천에서의 컬럼 순서대로 (API 015). 사라진 컬럼(isPresent=false)도 함께 돌려준다. */
    List<SourceColumn> findBySourceResourceIdOrderByOrdinalPositionAscIdAsc(Long sourceResourceId);

    /** 여러 자원의 현재 컬럼(isPresent=true)만. 순서는 정하지 않는다 (API 018 분석 대상 컬럼 확인). */
    List<SourceColumn> findBySourceResourceIdInAndIsPresentTrue(Collection<Long> sourceResourceIds);
}
