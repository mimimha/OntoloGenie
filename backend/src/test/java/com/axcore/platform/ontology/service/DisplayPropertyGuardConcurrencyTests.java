package com.axcore.platform.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.dto.ObjectTypeUpdateRequest;
import com.axcore.platform.ontology.dto.PropertyUpdateRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대표 속성 지정(024)과 속성 민감 전환(028)이 동시에 와도 민감 대표 속성이 생기지 않는지 실제 DB로 확인한다(S15P31C106-205 결정 D2). 한쪽이
 * 커밋되기 전에는 다른 쪽이 속성 행 잠금에서 기다리고, 커밋 뒤에는 최신 값을 보고 거절된다.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DisplayPropertyGuardConcurrencyTests {

    @Autowired ObjectTypeService objectTypeService;

    @Autowired PropertyService propertyService;

    @Autowired JdbcClient jdbcClient;

    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void restrictingPropertyWaitsForDisplayAssignmentAndIsRejected() throws Exception {
        long typeId = objectType();
        long propertyId = property(typeId);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        CompletableFuture<Void>[] restrict = new CompletableFuture[1];
        outer.executeWithoutResult(
                status -> {
                    objectTypeService.update(typeId, displayRequest(propertyId));
                    restrict[0] =
                            CompletableFuture.runAsync(
                                    () -> propertyService.update(propertyId, restrictRequest()));
                    assertThatThrownBy(() -> restrict[0].get(500, TimeUnit.MILLISECONDS))
                            .as("커밋 전에는 속성 행 잠금에서 기다린다")
                            .isInstanceOf(TimeoutException.class);
                });

        assertThatThrownBy(() -> restrict[0].get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION));
        assertThat(stateOf(typeId, propertyId))
                .containsEntry("display_property_id", propertyId)
                .containsEntry("sensitivity", "NORMAL");
    }

    @Test
    void displayAssignmentWaitsForRestrictionAndIsRejected() throws Exception {
        long typeId = objectType();
        long propertyId = property(typeId);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        CompletableFuture<Void>[] assign = new CompletableFuture[1];
        outer.executeWithoutResult(
                status -> {
                    propertyService.update(propertyId, restrictRequest());
                    assign[0] =
                            CompletableFuture.runAsync(
                                    () ->
                                            objectTypeService.update(
                                                    typeId, displayRequest(propertyId)));
                    assertThatThrownBy(() -> assign[0].get(500, TimeUnit.MILLISECONDS))
                            .as("커밋 전에는 속성 행 잠금에서 기다린다")
                            .isInstanceOf(TimeoutException.class);
                });

        assertThatThrownBy(() -> assign[0].get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(BusinessException.class);
        Map<String, Object> state = stateOf(typeId, propertyId);
        assertThat(state.get("display_property_id")).isNull();
        assertThat(state).containsEntry("sensitivity", "RESTRICTED");
    }

    private static ObjectTypeUpdateRequest displayRequest(long propertyId) {
        ObjectTypeUpdateRequest request = new ObjectTypeUpdateRequest();
        request.setDisplayPropertyId(propertyId);
        request.setRowVersion(0L);
        return request;
    }

    private static PropertyUpdateRequest restrictRequest() {
        PropertyUpdateRequest request = new PropertyUpdateRequest();
        request.setName("이름");
        request.setDataType("STRING");
        request.setIsRequired(false);
        request.setSensitivity("RESTRICTED");
        request.setRowVersion(0L);
        return request;
    }

    private long objectType() {
        return jdbcClient
                .sql("insert into object_type (code, name) values (:code, '설비') returning id")
                .param("code", "GUARD_" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class)
                .single();
    }

    private long property(long typeId) {
        return jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type)
                        values (:type, 'name', '이름', 'STRING') returning id
                        """)
                .param("type", typeId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> stateOf(long typeId, long propertyId) {
        return jdbcClient
                .sql(
                        """
                        select t.display_property_id, p.sensitivity
                        from object_type t, property_definition p
                        where t.id = :type and p.id = :property
                        """)
                .param("type", typeId)
                .param("property", propertyId)
                .query()
                .singleRow();
    }
}
