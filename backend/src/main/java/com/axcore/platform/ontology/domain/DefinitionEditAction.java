package com.axcore.platform.ontology.domain;

import com.axcore.platform.audit.domain.ChangeKind;

/**
 * 관리자가 정의 API로 직접 편집하는 동작(Jira 187, V18). 이름이 그대로 관리 Action 코드다. 후보 승인으로 바뀐 정의는 {@code
 * DECIDE_PROPOSAL}로 남으므로, 044 정의 변경 이력에서 Action 코드로 둘을 구분한다.
 */
public enum DefinitionEditAction {
    CREATE_OBJECT_TYPE("object_type", ChangeKind.INSERT),
    UPDATE_OBJECT_TYPE("object_type", ChangeKind.UPDATE),
    DELETE_OBJECT_TYPE("object_type", ChangeKind.DELETE),
    CREATE_PROPERTY("property_definition", ChangeKind.INSERT),
    UPDATE_PROPERTY("property_definition", ChangeKind.UPDATE),
    DELETE_PROPERTY("property_definition", ChangeKind.DELETE),
    CREATE_LINK_TYPE("link_type", ChangeKind.INSERT),
    UPDATE_LINK_TYPE("link_type", ChangeKind.UPDATE),
    DELETE_LINK_TYPE("link_type", ChangeKind.DELETE),
    CREATE_OBJECT_MAPPING("object_source_mapping", ChangeKind.INSERT),
    UPDATE_OBJECT_MAPPING("object_source_mapping", ChangeKind.UPDATE),
    CREATE_PROPERTY_MAPPING("property_source_mapping", ChangeKind.INSERT),
    UPDATE_PROPERTY_MAPPING("property_source_mapping", ChangeKind.UPDATE),
    DELETE_PROPERTY_MAPPING("property_source_mapping", ChangeKind.DELETE);

    /** 관리 Action의 권한 코드(V18). */
    public static final String PERMISSION = "ONTOLOGY_MANAGE";

    private final String table;
    private final ChangeKind kind;

    DefinitionEditAction(String table, ChangeKind kind) {
        this.table = table;
        this.kind = kind;
    }

    /** 바뀌는 정의 테이블. 감사 기록의 대상 종류(target_type)와 entity_change.table_name에 쓴다. */
    public String table() {
        return table;
    }

    public ChangeKind kind() {
        return kind;
    }
}
