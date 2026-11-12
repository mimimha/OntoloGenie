package com.axcore.platform.check.service;

import com.axcore.platform.ontology.dto.LinkRuleApproved;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연결 규칙 개정본을 승인한 트랜잭션이 커밋되면 SYNC 검사를 바로 넣는다(366). 승인 반영은 미연결을 기록하지 않으므로, 새 개정본의 판정이 다음 주기 ({@code
 * axcore.check.sync.interval})를 기다리지 않게 한다.
 *
 * <p>커밋 뒤라 새 트랜잭션에서 넣는다. 넣지 못해도 승인은 이미 끝났으므로 오류를 승인 응답으로 올리지 않고 남기기만 한다. 다음 주기의 SYNC가 같은 일을 한다. 주기
 * SYNC와 같은 스위치({@code axcore.check.sync.enabled})로 끈다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "axcore.check.sync.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SyncCheckOnRuleApproval {

    private final SyncCheckRequester requester;
    private final TransactionTemplate transaction;

    public SyncCheckOnRuleApproval(
            SyncCheckRequester requester, PlatformTransactionManager transactionManager) {
        this.requester = requester;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void onApproved(LinkRuleApproved event) {
        try {
            transaction.executeWithoutResult(status -> requester.request());
        } catch (RuntimeException e) {
            log.warn("규칙 개정본 {} 승인 뒤 SYNC 검사를 넣지 못했습니다. 다음 주기에 넣습니다.", event.linkMappingId(), e);
        }
    }
}
