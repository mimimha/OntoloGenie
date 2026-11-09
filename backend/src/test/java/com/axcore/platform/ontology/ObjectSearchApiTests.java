package com.axcore.platform.ontology;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ObjectSearchApiTests {

    private static final String PATH = "/api/v1/objects";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void scopesListAndCountBeforePaginationAndMasksOnlyRestrictedProperties() throws Exception {
        String tag = UUID.randomUUID().toString();
        long hidden = type(tag + "-hidden", tag + "-private");
        long ungrouped = type(tag + "-ungrouped", null);
        long visible = type(tag + "-visible", tag);
        allowViewer(tag);
        property(visible, "cost", "INTEGER", "RESTRICTED", null);
        property(visible, "effective_at", "DATETIME", "NORMAL", "AS_OF");
        object(hidden, tag + " press hidden", "{}");
        object(ungrouped, tag + " press ungrouped", "{}");
        long first =
                object(
                        visible,
                        tag + " Press",
                        """
                        {"cost":1200,"note":null,"effective_at":"2026-11-01T09:30:00+09:00"}
                        """);
        object(visible, tag + " press second", "{}");

        mvc.perform(get(PATH).param("q", tag + " press").param("size", "1").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) first))
                .andExpect(jsonPath("$.data[0].objectTypeName").value(tag + "-visible"))
                .andExpect(jsonPath("$.data[0].properties.cost").value(nullValue()))
                .andExpect(jsonPath("$.data[0].properties.note").value(nullValue()))
                .andExpect(jsonPath("$.data[0].restrictedPropertyCodes[0]").value("cost"))
                .andExpect(jsonPath("$.data[0].restrictedPropertyCodes.length()").value(1))
                .andExpect(jsonPath("$.data[0].dataAsOf").value("2026-11-01T00:30:00Z"))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

        mvc.perform(
                        get(PATH)
                                .param("objectTypeId", Long.toString(visible))
                                .param("q", "  ")
                                .param("page", Integer.toString(Integer.MAX_VALUE))
                                .param("size", "1")
                                .with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mvc.perform(get(PATH).param("objectTypeId", Long.toString(hidden)).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mvc.perform(get(PATH).param("q", tag + " press").with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(4))
                .andExpect(jsonPath("$.data[2].properties.cost").value(1200))
                .andExpect(jsonPath("$.data[2].restrictedPropertyCodes").isEmpty());
    }

    @Test
    void usesSeededViewerScopeForSearchResultsAndCount() throws Exception {
        String tag = UUID.randomUUID().toString();
        long production = type(tag + "-equipment", "PRODUCTION");
        long finance = type(tag + "-entry", "FINANCE");
        property(production, "cost", "INTEGER", "RESTRICTED", null);
        long visible = object(production, tag + " equipment", "{\"cost\":1200}");
        object(finance, tag + " entry", "{}");

        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) visible))
                .andExpect(jsonPath("$.data[0].properties.cost").value(nullValue()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        mvc.perform(get(PATH).param("q", tag + " entry").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void validatesSessionPageSizeAndDeletedStatus() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(PATH)
                                .with(
                                        authentication(
                                                new LoginAccount(1L, "tester", "ADMIN", true)
                                                        .toAuthentication())))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).param("size", "101").with(actor("VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("size"));
        mvc.perform(get(PATH).param("status", "UNKNOWN").with(actor("VIEWER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("status", "DELETED_AT_SOURCE").with(actor("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("원천에서 삭제된 객체는 관리자만 조회할 수 있습니다."))
                .andExpect(jsonPath("$.error.details.field").value("status"));
    }

    @Test
    void treatsSearchWildcardsAsLiteralCharacters() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, null);
        object(type, tag + " Press 100%", "{}");
        object(type, tag + " Press 1000", "{}");

        mvc.perform(get(PATH).param("q", tag + " press 100%").with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value(tag + " Press 100%"));
    }

    @Test
    void masksRestrictedSourceKeyAndNameBeforeSearchingForTheValue() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long property = property(type, "serial", "STRING", "RESTRICTED", null);
        long mapping = restrictedKeyMapping(type, property, tag);
        long active = sourceObject(type, mapping, tag + "-active", "ACTIVE");
        sourceObject(type, mapping, tag + "-deleted", "DELETED_AT_SOURCE");

        mvc.perform(get(PATH).param("objectTypeId", Long.toString(type)).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value("#" + active))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(nullValue()))
                .andExpect(jsonPath("$.data[0].properties.serial").value(nullValue()));
        mvc.perform(get(PATH).param("q", tag).with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mvc.perform(get(PATH).param("q", tag).with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(tag + "-active"));
        mvc.perform(
                        get(PATH)
                                .param("q", tag)
                                .param("status", "DELETED_AT_SOURCE")
                                .with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("DELETED_AT_SOURCE"));
    }

    @Test
    void neverSearchesTheStaleDisplayNameOrUnclassifiedKeyAfterAMappingChange() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        long restricted = property(type, "serial", "STRING", "RESTRICTED", null);
        long label = property(type, "public_label", "STRING", "NORMAL", null);
        long mapping = restrictedKeyMapping(type, restricted, tag);
        long id = sourceObject(type, mapping, tag + "-sensitive", "ACTIVE");
        jdbc.sql("delete from property_source_mapping where source_mapping_id = :mapping")
                .param("mapping", mapping)
                .update();
        jdbc.sql("update object_type set display_property_id = :label where id = :type")
                .param("label", label)
                .param("type", type)
                .update();
        jdbc.sql(
                        """
                        update object_instance
                        set properties = properties || jsonb_build_object('public_label', cast(:name as text))
                        where id = :id
                        """)
                .param("name", tag + "-readable")
                .param("id", id)
                .update();

        mvc.perform(get(PATH).param("q", tag + "-sensitive").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mvc.perform(get(PATH).param("q", tag + "-readable").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayName").value(tag + "-readable"))
                .andExpect(jsonPath("$.data[0].sourceKey.serial").value(nullValue()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void searchesOnlyIdentifiersWhoseValuesTheActorCanRead() throws Exception {
        String tag = UUID.randomUUID().toString();
        long type = type(tag, tag);
        allowViewer(tag);
        property(type, "equipment_code", "STRING", "NORMAL", null);
        property(type, "serial", "STRING", "RESTRICTED", null);
        jdbc.sql("update property_definition set is_identifier = true where object_type_id = :type")
                .param("type", type)
                .update();
        object(
                type,
                "Press",
                """
                {"equipment_code":"%s-public","serial":"%s-private"}
                """
                        .formatted(tag, tag));

        mvc.perform(get(PATH).param("q", tag + "-public").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
        mvc.perform(get(PATH).param("q", tag + "-private").with(actor("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mvc.perform(get(PATH).param("q", tag + "-private").with(actor("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    private static RequestPostProcessor actor(String role) {
        return authentication(new LoginAccount(1L, "tester", role, false).toAuthentication());
    }

    private long type(String name, String accessGroup) {
        return jdbc.sql(
                        """
                        insert into object_type (code, name, access_group)
                        values (:code, :code, :group) returning id
                        """)
                .param("code", name)
                .param("group", accessGroup)
                .query(Long.class)
                .single();
    }

    private void allowViewer(String accessGroup) {
        jdbc.sql(
                        """
                        insert into role_data_scope (role_id, access_group, can_view_restricted)
                        select id, :group, false from role where code = 'VIEWER'
                        """)
                .param("group", accessGroup)
                .update();
    }

    private long property(
            long type, String code, String dataType, String sensitivity, String temporalRole) {
        return jdbc.sql(
                        """
                        insert into property_definition
                            (object_type_id, code, name, data_type, sensitivity, temporal_role)
                        values (:type, :code, :code, :dataType, :sensitivity, :temporalRole) returning id
                        """)
                .param("type", type)
                .param("code", code)
                .param("dataType", dataType)
                .param("sensitivity", sensitivity)
                .param("temporalRole", temporalRole)
                .query(Long.class)
                .single();
    }

    private long restrictedKeyMapping(long type, long property, String code) {
        long resource =
                jdbc.sql(
                                """
                        with source as (
                            insert into data_source (code, name, source_kind)
                            values (:code, :code, 'POSTGRES') returning id
                        )
                        insert into source_resource (data_source_id, resource_key, resource_kind)
                        select id, 'public.equipment', 'TABLE' from source returning id
                        """)
                        .param("code", code)
                        .query(Long.class)
                        .single();
        long mapping =
                jdbc.sql(
                                """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns)
                        values (:resource, :type, '["serial"]'::jsonb) returning id
                        """)
                        .param("resource", resource)
                        .param("type", type)
                        .query(Long.class)
                        .single();
        long column =
                jdbc.sql(
                                """
                        insert into source_column
                            (source_resource_id, column_name, source_data_type, is_nullable, ordinal_position)
                        values (:resource, 'serial', 'text', false, 1) returning id
                        """)
                        .param("resource", resource)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into property_source_mapping
                            (source_mapping_id, source_resource_id, property_definition_id, source_column_id)
                        values (:mapping, :resource, :property, :column)
                        """)
                .param("mapping", mapping)
                .param("resource", resource)
                .param("property", property)
                .param("column", column)
                .update();
        return mapping;
    }

    private long sourceObject(long type, long mapping, String serial, String status) {
        return jdbc.sql(
                        """
                        insert into object_instance
                            (object_type_id, source_mapping_id, source_key, display_name, properties, status)
                        values (:type, :mapping, jsonb_build_object('serial', cast(:serial as text)),
                                :serial, jsonb_build_object('serial', cast(:serial as text)), :status)
                        returning id
                        """)
                .param("type", type)
                .param("mapping", mapping)
                .param("serial", serial)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long object(long type, String name, String properties) {
        return jdbc.sql(
                        """
                        insert into object_instance (object_type_id, display_name, properties)
                        values (:type, :name, cast(:properties as jsonb)) returning id
                        """)
                .param("type", type)
                .param("name", name)
                .param("properties", properties)
                .query(Long.class)
                .single();
    }
}
