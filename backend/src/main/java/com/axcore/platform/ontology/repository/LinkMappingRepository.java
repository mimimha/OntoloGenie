package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkMappingRepository extends JpaRepository<LinkMapping, Long> {

    List<LinkMapping> findByStatusOrderById(LinkMappingStatus status);
}
