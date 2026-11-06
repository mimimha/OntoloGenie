package com.axcore.platform.action.service;

import java.util.Map;

/**
 * 서버에 Handler가 없는 업무 Action 정의가 저장됐다(Action 정의서 4-3). 088 등록 결과가 handlerReady=false이거나 090이
 * handlerKey를 아직 없는 키로 바꿨을 때 정의 저장 트랜잭션 안에서 발행하고, 커밋된 뒤 {@link HandlerRequestNotifier}가 개발자에게 알린다.
 *
 * @param description 관리자가 적은 Action 설명. 개발자가 Handler가 할 일을 알 수 있는 유일한 글이다
 * @param objectType 대상 객체 유형 "코드(이름)". 대상 유형을 정하지 않았으면 null
 * @param requestedBy 이번에 등록·수정한 관리자 "이름(로그인 ID)"
 * @param createdBy 이 정의를 처음 등록한 관리자 "이름(로그인 ID)". 개발자가 Handler가 할 일을 물어볼 사람이다. 등록 기록이 없으면 null
 */
public record HandlerRequested(
        Reason reason,
        String code,
        String name,
        String description,
        String handlerKey,
        String targetKind,
        String objectType,
        String requiredPermission,
        Map<String, Object> inputSchema,
        boolean active,
        String requestedBy,
        String createdBy) {

    public enum Reason {
        CREATED,
        HANDLER_CHANGED
    }
}
