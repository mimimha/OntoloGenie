package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest.Direction;

/**
 * 기준 객체의 활성 관계를 관계 유형·방향별로 묶은 건수. direction은 OUT 또는 IN이고, 같은 linkTypeId·direction으로 078을 부르면 그 묶음의
 * 연결을 쪽 단위로 받는다.
 */
public record ObjectLinkGroupResponse(
        long linkTypeId,
        String linkTypeName,
        Direction direction,
        long relatedObjectTypeId,
        String relatedObjectTypeName,
        long linkCount) {}
