package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.domain.SyncStrategy;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceResourceRepository extends JpaRepository<SourceResource, Long> {

    Optional<SourceResource> findByIdAndDataSourceId(Long id, Long dataSourceId);

    /** CDC 이벤트의 표({스키마}.{이름})를 찾는다(198-5). */
    Optional<SourceResource> findByDataSourceIdAndResourceKey(
            Long dataSourceId, String resourceKey);

    /** 원천 하나의 CDC 테이블. 복구가 필요하면 모두 다시 읽기 대상으로 표시한다(198-5·199). */
    List<SourceResource> findByDataSourceIdAndSyncStrategy(
            Long dataSourceId, SyncStrategy syncStrategy);

    /** 매핑 변경과 수집이 같은 테이블을 동시에 다루지 않도록 행을 잠근다. 수집도 같은 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SourceResource r where r.id = :id")
    Optional<SourceResource> findForUpdate(@Param("id") Long id);

    /**
     * CDC 반영(198-5)이 쓸 원천 테이블 행을 ID 순서로 공유 잠금한다(PostgreSQL {@code FOR SHARE}). 전체 읽기 저장(197 {@code
     * complete})과 매핑 변경은 같은 행을 {@code FOR UPDATE}로 잡으므로, 원천 테이블 행 → 객체 행 순서가 같아져 교착이 생기지 않고 반영하는 동안
     * 매핑이 바뀌지 않는다. CDC 소비자끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from SourceResource r where r.id in :ids order by r.id")
    List<SourceResource> lockAllShared(@Param("ids") Collection<Long> ids);

    /** 한 원천의 자원 목록. 조건이 null이면 거르지 않고, pattern(ILIKE)은 resource_key에 건다. 이름순 (API 014). */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select * from source_resource r
                    where r.data_source_id = :dataSourceId
                      and (cast(:resourceKind as varchar) is null or r.resource_kind = :resourceKind)
                      and (cast(:isReadable as boolean) is null or r.is_readable = :isReadable)
                      and r.resource_key ilike :pattern
                    order by r.resource_key, r.id
                    """,
            countQuery =
                    """
                    select count(*) from source_resource r
                    where r.data_source_id = :dataSourceId
                      and (cast(:resourceKind as varchar) is null or r.resource_kind = :resourceKind)
                      and (cast(:isReadable as boolean) is null or r.is_readable = :isReadable)
                      and r.resource_key ilike :pattern
                    """)
    Page<SourceResource> search(
            @Param("dataSourceId") Long dataSourceId,
            @Param("resourceKind") String resourceKind,
            @Param("isReadable") Boolean isReadable,
            @Param("pattern") String pattern,
            Pageable pageable);
}
