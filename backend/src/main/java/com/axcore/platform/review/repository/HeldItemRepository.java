package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.HeldItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HeldItemRepository
        extends JpaRepository<HeldItem, Long>, JpaSpecificationExecutor<HeldItem> {

    List<HeldItem> findByLinkTypeIdAndStatus(Long linkTypeId, String status);

    @Query(
            "select h.sourceObjectId from HeldItem h where h.linkTypeId = :linkTypeId and h.status = :status")
    List<Long> findSourceObjectIdsByLinkTypeAndStatus(
            @Param("linkTypeId") Long linkTypeId, @Param("status") String status);
}
