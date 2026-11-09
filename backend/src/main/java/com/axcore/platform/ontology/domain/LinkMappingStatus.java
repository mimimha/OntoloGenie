package com.axcore.platform.ontology.domain;

/** 연결 규칙 개정본의 상태. 규칙 코드마다 ACTIVE 개정본은 하나이고, RETIRED가 된 개정본은 다시 ACTIVE가 되지 않는다(DB 트리거). */
public enum LinkMappingStatus {
    ACTIVE,
    RETIRED
}
