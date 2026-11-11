package com.axcore.platform.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.ontology.dto.ModelGraphEdgeResponse;
import com.axcore.platform.ontology.dto.ModelGraphNodeResponse;
import com.axcore.platform.ontology.repository.ModelGraphQueryRepository;
import com.axcore.platform.ontology.service.ModelGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "test.context=model-graph-query")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ModelGraphQueryIntegrationTests {

    @Autowired ModelGraphService service;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ModelGraphQueryRepository graphQuery;

    @Test
    void readsNodesAndEdgesFromTheSameSnapshotWhenDefinitionsAreAdded() {
        var writer = new TransactionTemplate(transactions);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(
                        invocation -> {
                            // 노드 조회가 끝난 뒤 별도 트랜잭션에서 새 유형과 관계를 커밋한다.
                            writer.executeWithoutResult(
                                    transaction -> {
                                        long type =
                                                jdbc.sql(
                                                                """
                                                                insert into object_type (code, name)
                                                                values ('NEW_TYPE', '새 유형') returning id
                                                                """)
                                                        .query(Long.class)
                                                        .single();
                                        jdbc.sql(
                                                        """
                                                        insert into link_type
                                                            (code, name, source_type_id, target_type_id, cardinality, description)
                                                        values ('NEW_RELATION', '새 관계', :type, :type, 'ONE_TO_ONE', '')
                                                        """)
                                                .param("type", type)
                                                .update();
                                    });
                            return invocation.callRealMethod();
                        })
                .doCallRealMethod()
                .when(graphQuery)
                .findModelGraphEdges();

        var first = service.getGraph();
        assertThat(first.nodes()).isEmpty();
        assertThat(first.edges()).isEmpty();

        var next = service.getGraph();
        assertThat(next.nodes())
                .extracting(ModelGraphNodeResponse::code)
                .containsExactly("NEW_TYPE");
        assertThat(next.edges())
                .extracting(ModelGraphEdgeResponse::code)
                .containsExactly("NEW_RELATION");
        long typeId = next.nodes().getFirst().id();
        assertThat(next.edges().getFirst().sourceTypeId()).isEqualTo(typeId);
        assertThat(next.edges().getFirst().targetTypeId()).isEqualTo(typeId);
    }
}
