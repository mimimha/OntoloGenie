package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkMappingRepository extends JpaRepository<LinkMapping, Long> {

    List<LinkMapping> findByStatusOrderById(LinkMappingStatus status);

    Optional<LinkMapping> findByCodeAndStatus(String code, LinkMappingStatus status);

    Optional<LinkMapping> findTopByCodeOrderByRevisionDesc(String code);

    List<LinkMapping> findByStatusAndLinkTypeIdInOrderById(
            LinkMappingStatus status, Collection<Long> linkTypeIds);
}
