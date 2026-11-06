package com.axcore.platform.agent.service;

import com.axcore.platform.agent.domain.AgentConversation;
import com.axcore.platform.agent.dto.AgentConversationResponse;
import com.axcore.platform.agent.repository.AgentConversationRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentConversationService {
    private final AgentConversationRepository repository;
    private final Clock clock;

    @Transactional
    public AgentConversationResponse create(long accountId) {
        // DB(timestamptz)는 마이크로초까지 저장하므로 응답과 다시 읽은 값이 같도록 맞춘다.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return AgentConversationResponse.from(
                repository.save(AgentConversation.start(accountId, now)));
    }

    public Page<AgentConversationResponse> search(long accountId, Pageable pageable) {
        return repository.findByAccountId(accountId, pageable).map(AgentConversationResponse::from);
    }

    /**
     * 임시(개발 데이터 정리용, 나중에 제거): 내 대화를 DB에서 지운다. 메시지는 함께 지워진다. 조회 기록이나 재배정 계획이 이 대화를 가리키면 DB가 막으므로
     * 409로 답한다.
     */
    @Transactional
    public void deleteTemporarily(long accountId, long id) {
        AgentConversation conversation =
                repository
                        .findByIdAndAccountId(id, accountId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "agent_conversation")));
        try {
            repository.delete(conversation);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT, "실행 기록이 있는 대화는 지울 수 없습니다.", Map.of());
        }
    }

    /** 다른 사용자의 대화는 없는 대화와 같게 404로 답한다(존재 여부를 알리지 않는다). */
    public AgentConversationResponse get(long accountId, long id) {
        return repository
                .findByIdAndAccountId(id, accountId)
                .map(AgentConversationResponse::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "agent_conversation")));
    }
}
