package com.axcore.platform.ontology.dto;

/** 078 응답의 활성 관계 한 건. relatedObject는 요청한 기준 객체의 연결 상대다. */
public record ObjectLinkResponse(
        long id,
        long linkTypeId,
        String linkTypeName,
        long sourceObjectId,
        long targetObjectId,
        RelatedObject relatedObject,
        Origin origin,
        String status,
        Long linkMappingId,
        ObjectLinkEvidenceResponse evidence,
        ExpressionKind expressionKind,
        Long confirmedBy) {

    public record RelatedObject(
            long id, String displayName, long objectTypeId, String objectTypeName) {}

    public enum Origin {
        RULE,
        DOCUMENT,
        MANUAL
    }

    public enum ExpressionKind {
        OBSERVED,
        ESTIMATED
    }
}
