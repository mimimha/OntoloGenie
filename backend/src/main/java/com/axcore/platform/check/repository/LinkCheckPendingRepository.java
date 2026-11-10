package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.LinkCheckPending;
import com.axcore.platform.check.domain.LinkCheckPending.Status;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LinkCheckPendingRepository extends JpaRepository<LinkCheckPending, Long> {

    /** 이 상태의 확인 대기가 하나라도 있는지(SYNC 요청이 주기마다 새로 넣을지 정한다). */
    boolean existsByStatus(Status status);

    /** 이 규칙 개정본의 대기 중인 것. 출발 객체마다 많아야 하나다(uq_link_check_pending_waiting). */
    List<LinkCheckPending> findByLinkMappingIdAndStatusOrderById(long linkMappingId, Status status);

    /** 규칙 개정본이 더 이상 반영 중(ACTIVE)이 아닌 대기. */
    @Query(
            """
            select p from LinkCheckPending p
            where p.status = com.axcore.platform.check.domain.LinkCheckPending.Status.WAITING
              and p.linkMappingId in (
                  select m.id from LinkMapping m
                  where m.status <> com.axcore.platform.ontology.domain.LinkMappingStatus.ACTIVE)
            order by p.id
            """)
    List<LinkCheckPending> findWaitingOfInactiveRules();
}
