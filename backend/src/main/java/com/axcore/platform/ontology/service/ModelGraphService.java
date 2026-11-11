package com.axcore.platform.ontology.service;

import com.axcore.platform.ontology.dto.ModelGraphResponse;
import com.axcore.platform.ontology.repository.ModelGraphQueryRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModelGraphService {

    private final ObjectTypeRepository objectTypes;
    private final ModelGraphQueryRepository graphQuery;

    /** 노드와 선을 같은 스냅샷에서 읽어 조회 중 추가된 관계가 응답에 없는 노드를 가리키지 않도록 한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelGraphResponse getGraph() {
        return new ModelGraphResponse(
                objectTypes.findModelGraphNodes(), graphQuery.findModelGraphEdges());
    }
}
