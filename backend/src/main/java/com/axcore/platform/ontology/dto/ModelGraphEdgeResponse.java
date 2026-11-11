package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.LinkCardinality;

public record ModelGraphEdgeResponse(
        Long id,
        Long sourceTypeId,
        Long targetTypeId,
        String code,
        String name,
        LinkCardinality cardinality,
        long relationCount,
        long activeLinkCount) {}
