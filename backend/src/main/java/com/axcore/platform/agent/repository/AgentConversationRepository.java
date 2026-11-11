package com.axcore.platform.agent.repository;

import com.axcore.platform.agent.domain.AgentConversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    Page<AgentConversation> findByAccountId(Long accountId, Pageable pageable);

    Optional<AgentConversation> findByIdAndAccountId(Long id, Long accountId);

    /**
     * 오래된 대화 요약을 저장한다(333). 요약을 만들기 전에 읽은 범위(expectedUntil, 요약이 없었으면 0)가 그대로일 때만 바꾼다. 같은 대화의 요약이
     * 동시에 만들어지면 먼저 저장한 것만 남고 나중 것은 0을 돌려준다.
     */
    @Transactional
    @Modifying
    @Query(
            """
            update AgentConversation c
               set c.summary = :summary, c.summarizedUntilMessageId = :until
             where c.id = :id
               and coalesce(c.summarizedUntilMessageId, 0) = :expectedUntil
            """)
    int saveSummary(
            @Param("id") Long id,
            @Param("expectedUntil") long expectedUntil,
            @Param("summary") String summary,
            @Param("until") long until);
}
