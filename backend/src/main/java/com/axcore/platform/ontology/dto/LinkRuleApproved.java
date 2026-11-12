package com.axcore.platform.ontology.dto;

/**
 * 연결 규칙 개정본을 승인해 반영했다(366). 커밋된 뒤 검사 쪽이 SYNC 검사를 바로 넣는다({@code
 * check.service.SyncCheckOnRuleApproval}).
 *
 * @param linkMappingId 반영한 규칙 개정본
 */
public record LinkRuleApproved(long linkMappingId) {}
