package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkMappingRepository extends JpaRepository<LinkMapping, Long> {

    List<LinkMapping> findByStatusOrderById(LinkMappingStatus status);

    /** 주어진 개정본 가운데 이 상태인 것(191 검사 접수: 반영 중인 규칙만 검사한다). */
    List<LinkMapping> findByIdInAndStatus(Collection<Long> ids, LinkMappingStatus status);

    Optional<LinkMapping> findByCodeAndStatus(String code, LinkMappingStatus status);

    Optional<LinkMapping> findTopByCodeOrderByRevisionDesc(String code);

    /** 규칙 코드의 개정본을 개정 번호 순으로(API 046). */
    Page<LinkMapping> findByCodeOrderByRevisionAsc(String code, Pageable pageable);

    /** 규칙 코드의 특정 개정본(API 047). */
    Optional<LinkMapping> findByCodeAndRevision(String code, int revision);

    List<LinkMapping> findByStatusAndLinkTypeIdInOrderById(
            LinkMappingStatus status, Collection<Long> linkTypeIds);
}
