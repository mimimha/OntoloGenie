package com.axcore.platform.ontology.dto;

import java.util.List;

public record ModelGraphResponse(
        List<ModelGraphNodeResponse> nodes, List<ModelGraphEdgeResponse> edges) {}
