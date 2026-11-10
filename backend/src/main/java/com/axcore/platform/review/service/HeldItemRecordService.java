package com.axcore.platform.review.service;

import com.axcore.platform.ontology.service.HeldItemRecorder;
import com.axcore.platform.review.domain.HeldItem;
import com.axcore.platform.review.repository.HeldItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관계 규칙 반영(189)이 만드는 보류 건 저장. 승인 트랜잭션(131) 안에서만 돈다. 보류 건의 처리(승인·종결·자동 해소)는 {@link HeldItemService}와
 * 238이 한다. 열린 보류 건은 관계 유형 단위로 한 번에 읽는다. 새 보류 건은 saveAll로 넘기지만 ID가 IDENTITY라 INSERT는 건마다 나간다.
 */
@Service
@RequiredArgsConstructor
public class HeldItemRecordService implements HeldItemRecorder {

    private static final String HELD = "HELD";

    private final HeldItemRepository repository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Long> openSourceObjectIds(long linkTypeId) {
        return Set.copyOf(repository.findSourceObjectIdsByLinkTypeAndStatus(linkTypeId, HELD));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> closeLinkedByRule(
            long linkTypeId, Map<Long, Long> linkBySource, long resolvedBy, String note) {
        if (linkBySource.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<Long> closed = new ArrayList<>();
        for (HeldItem item : repository.findByLinkTypeIdAndStatus(linkTypeId, HELD)) {
            Long linkId = linkBySource.get(item.getSourceObjectId());
            if (linkId != null) {
                item.approveByRule(linkId, resolvedBy, note, now);
                closed.add(item.getId());
            }
        }
        return closed;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> holdAll(List<NewHeldItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        return repository
                .saveAll(
                        items.stream()
                                .map(
                                        item ->
                                                HeldItem.held(
                                                        item.proposalItemId(),
                                                        item.linkMappingId(),
                                                        item.linkTypeId(),
                                                        item.sourceObjectId(),
                                                        item.rawValue(),
                                                        item.transformedValue(),
                                                        item.holdReason(),
                                                        now))
                                .toList())
                .stream()
                .map(HeldItem::getId)
                .toList();
    }
}
