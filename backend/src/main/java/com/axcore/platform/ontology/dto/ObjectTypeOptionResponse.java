package com.axcore.platform.ontology.dto;

/** 객체 검색 조건의 유형 선택지 하나(347). 화면 드롭다운이 쓰는 값만 담는다. */
public record ObjectTypeOptionResponse(long id, String code, String name) {}
