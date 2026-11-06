package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.AgentMessage;
import com.axcore.platform.agent.domain.MessageSender;
import com.axcore.platform.agent.domain.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    Page<AgentMessage> findByConversationId(Long conversationId, Pageable pageable);

    boolean existsByConversationIdAndStatus(Long conversationId, MessageStatus status);

    List<AgentMessage> findByConversationIdAndStatusAndCreatedAtBefore(
            Long conversationId, MessageStatus status, Instant before);

    Optional<AgentMessage> findFirstByConversationIdAndSenderOrderByIdDesc(
            Long conversationId, MessageSender sender);

    /** 메시지가 속한 대화 ID. 재배정 계획을 Agent 대화에 연결할 때 쓴다. 메시지가 없으면 비어 있다. */
    @Query("select m.conversationId from AgentMessage m where m.id = :id")
    Optional<Long> findConversationIdById(@Param("id") Long id);
}
