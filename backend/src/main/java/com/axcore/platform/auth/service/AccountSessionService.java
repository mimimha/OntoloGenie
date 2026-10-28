package com.axcore.platform.auth.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 계정의 세션을 찾아 끊는다 (개발 가이드 11.1). 비밀번호 변경(API 003)은 {@link #expireOthers}, 계정 잠금·비활성화·역할 변경(API 007)과
 * 비밀번호 초기화(API 103)는 {@link #expireAll}을 쓴다. 세션 저장소는 웹 서버에만 있어 배치 컨테이너에서는 만들지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AccountSessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public void expireAll(Long accountId) {
        sessionsOf(accountId).keySet().forEach(sessionRepository::deleteById);
    }

    /** 지금 요청을 보낸 세션만 남기고 같은 계정의 다른 세션을 끊는다. */
    public void expireOthers(Long accountId, String currentSessionId) {
        sessionsOf(accountId).keySet().stream()
                .filter(sessionId -> !sessionId.equals(currentSessionId))
                .forEach(sessionRepository::deleteById);
    }

    private Map<String, ? extends Session> sessionsOf(Long accountId) {
        return sessionRepository.findByPrincipalName(String.valueOf(accountId));
    }
}
