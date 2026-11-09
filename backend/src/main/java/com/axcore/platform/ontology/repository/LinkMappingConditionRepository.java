package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkMappingCondition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkMappingConditionRepository extends JpaRepository<LinkMappingCondition, Long> {

    List<LinkMappingCondition> findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
            Collection<Long> linkMappingIds);
}
