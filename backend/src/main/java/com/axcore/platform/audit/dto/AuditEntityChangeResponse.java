package com.axcore.platform.audit.dto;

import com.axcore.platform.audit.domain.ChangeKind;
import com.axcore.platform.audit.domain.EntityChange;
import java.util.Map;

/** 실행이 바꾼 행 하나 (API 098). INSERT는 beforeData, DELETE는 afterData가 없다. */
public record AuditEntityChangeResponse(
        String tableName,
        Map<String, Object> rowKey,
        ChangeKind changeKind,
        Map<String, Object> beforeData,
        Map<String, Object> afterData) {

    public static AuditEntityChangeResponse from(EntityChange change) {
        return new AuditEntityChangeResponse(
                change.getTableName(),
                change.getRowKey(),
                change.getChangeKind(),
                change.getBeforeData(),
                change.getAfterData());
    }
}
