package com.axcore.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.auth.service.MaskedProperties;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 객체·민감 속성 조회 범위(S15P31C106-205)를 실제 DB로 확인한다. 기준(2026-11-06 결정): ADMIN은 전부, 그 밖의 역할은 허용 그룹만, 그룹 없는
 * 유형은 ADMIN만, 민감 값은 허용 행의 can_view_restricted가 true일 때만, 볼 수 없는 객체는 404.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DataAccessPolicyTests {

    @Autowired DataAccessPolicy policy;

    @Autowired JdbcClient jdbcClient;

    /** VIEWER에 허용하고 민감 값도 열어 준 그룹의 유형. */
    long openType;

    /** VIEWER에 허용했지만 민감 값은 막은 그룹의 유형. */
    long closedType;

    /** 어느 역할에도 허용하지 않은 그룹의 유형. */
    long unlistedType;

    /** 접근 그룹이 없는 유형. */
    long ungroupedType;

    @BeforeEach
    void setUp() {
        String open = group();
        String closed = group();
        openType = objectType(open);
        closedType = objectType(closed);
        unlistedType = objectType(group());
        ungroupedType = objectType(null);
        allow("VIEWER", open, true);
        allow("VIEWER", closed, false);
        for (long type : new long[] {openType, closedType, unlistedType, ungroupedType}) {
            property(type, "equipment_code", "NORMAL");
            property(type, "purchase_price", "RESTRICTED");
            property(type, "owner_phone", "RESTRICTED");
        }
    }

    @Test
    void adminSeesEveryTypeAndRestrictedValues() {
        DataAccess access = policy.of(account("ADMIN"));

        assertThat(access.visibleObjectTypeIds())
                .contains(openType, closedType, unlistedType, ungroupedType);
        assertThat(access.canViewRestricted(ungroupedType)).isTrue();
        assertThat(access.mask(ungroupedType, values()).restrictedPropertyCodes()).isEmpty();
    }

    @Test
    void otherRolesSeeOnlyAllowedGroups() {
        DataAccess access = policy.of(account("VIEWER"));

        assertThat(access.visibleObjectTypeIds())
                .contains(openType, closedType)
                .doesNotContain(unlistedType, ungroupedType);
        assertThat(access.canView(unlistedType)).isFalse();
        assertThat(access.canView(ungroupedType)).isFalse();
    }

    @Test
    void roleWithoutAnyAllowedGroupSeesNothingOfTheseTypes() {
        DataAccess access = policy.of(account("PLANNER"));

        assertThat(access.visibleObjectTypeIds())
                .doesNotContain(openType, closedType, unlistedType, ungroupedType);
    }

    @Test
    void unknownTypeIsNotVisible() {
        DataAccess access = policy.of(account("ADMIN"));

        assertThat(access.canView(Long.MAX_VALUE)).isFalse();
        assertThatThrownBy(() -> access.mask(Long.MAX_VALUE, values()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invisibleObjectIsRejectedAsNotFound() {
        DataAccess access = policy.of(account("VIEWER"));

        access.requireVisible(openType, "object_instance", 1L);
        assertThatThrownBy(() -> access.requireVisible(ungroupedType, "object_instance", 7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> {
                            assertThat(error.getErrorCode())
                                    .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                            assertThat(error.getDetails())
                                    .containsEntry("resource", "object_instance")
                                    .containsEntry("id", 7L);
                        });
    }

    @Test
    void allowedRestrictedViewKeepsValues() {
        MaskedProperties masked = policy.of(account("VIEWER")).mask(openType, values());

        assertThat(masked.properties()).isEqualTo(values());
        assertThat(masked.restrictedPropertyCodes()).isEmpty();
    }

    @Test
    void blockedRestrictedViewHidesValuesButKeepsKeys() {
        Map<String, Object> original = values();

        MaskedProperties masked = policy.of(account("VIEWER")).mask(closedType, original);

        assertThat(masked.properties())
                .containsEntry("equipment_code", "PR-03")
                .containsEntry("purchase_price", null)
                .containsEntry("note", null)
                .doesNotContainKey("owner_phone");
        assertThat(masked.restrictedPropertyCodes()).containsExactly("purchase_price");
        assertThat(original).containsEntry("purchase_price", 1200);
    }

    @Test
    void restrictedPropertyWithEmptyValueIsStillReportedAsMasked() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("purchase_price", null);
        properties.put("owner_phone", "010-0000-0000");

        MaskedProperties masked = policy.of(account("VIEWER")).mask(closedType, properties);

        assertThat(masked.properties())
                .containsEntry("purchase_price", null)
                .containsEntry("owner_phone", null);
        assertThat(masked.restrictedPropertyCodes())
                .containsExactly("owner_phone", "purchase_price");
    }

    /** 범위를 따지지 않고 부른 경우에도 민감 값은 새지 않는다. */
    @Test
    void invisibleTypeIsMaskedToo() {
        MaskedProperties masked = policy.of(account("VIEWER")).mask(ungroupedType, values());

        assertThat(masked.properties()).containsEntry("purchase_price", null);
        assertThat(masked.restrictedPropertyCodes()).containsExactly("purchase_price");
    }

    @Test
    void hidesRestrictedValueByTypeCode() {
        DataAccess viewer = policy.of(account("VIEWER"));

        assertThat(viewer.hidesValue(codeOf(closedType), "purchase_price")).isTrue();
        assertThat(viewer.hidesValue(codeOf(closedType), "equipment_code")).isFalse();
        assertThat(viewer.hidesValue(codeOf(openType), "purchase_price")).isFalse();
        assertThat(viewer.hidesValue(codeOf(ungroupedType), "purchase_price")).isTrue();
        assertThat(viewer.hidesValue("no_such_type", "purchase_price")).isFalse();
        assertThat(policy.of(account("ADMIN")).hidesValue(codeOf(closedType), "purchase_price"))
                .isFalse();
    }

    @Test
    void scopeReflectsRoleSettingChangesOnNextRead() {
        String later = group();
        long type = objectType(later);

        assertThat(policy.of(account("MAINTENANCE")).canView(type)).isFalse();

        allow("MAINTENANCE", later, false);

        assertThat(policy.of(account("MAINTENANCE")).canView(type)).isTrue();
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("equipment_code", "PR-03");
        values.put("purchase_price", 1200);
        values.put("note", null);
        return values;
    }

    private static LoginAccount account(String roleCode) {
        return new LoginAccount(1L, "tester", roleCode, false);
    }

    private static String group() {
        return "group-" + UUID.randomUUID();
    }

    private long objectType(String accessGroup) {
        String code = "scope_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcClient
                .sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", code)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private String codeOf(long objectTypeId) {
        return jdbcClient
                .sql("select code from object_type where id = :id")
                .param("id", objectTypeId)
                .query(String.class)
                .single();
    }

    private void property(long objectTypeId, String code, String sensitivity) {
        jdbcClient
                .sql(
                        """
                        insert into property_definition (object_type_id, code, name, data_type, sensitivity)
                        values (:type, :code, :code, 'STRING', :sensitivity)
                        """)
                .param("type", objectTypeId)
                .param("code", code)
                .param("sensitivity", sensitivity)
                .update();
    }

    private void allow(String roleCode, String accessGroup, boolean canViewRestricted) {
        jdbcClient
                .sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, :restricted from role where code = :role
                        """)
                .param("group", accessGroup)
                .param("restricted", canViewRestricted)
                .param("role", roleCode)
                .update();
    }
}
