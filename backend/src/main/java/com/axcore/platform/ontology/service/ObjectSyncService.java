package com.axcore.platform.ontology.service;

import com.axcore.platform.ontology.domain.FullLoad;
import com.axcore.platform.ontology.domain.SourceChange;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.ontology.repository.ObjectSyncRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집이 원천 객체를 저장하는 내부 서비스(FN-ONT-04·FN-ISS-05, Jira 180). 화면·외부 API는 없다. 전체 읽기(197)와 CDC 소비자(198)가 연
 * 트랜잭션 안에서만 돈다. CDC 소비자는 객체 반영과 cdc_offset 저장을 한 트랜잭션에 넣어야 하기 때문이다.
 *
 * <p>같은 원천 행은 (매핑, 원천 키)로 늘 같은 객체 ID를 쓴다. 원천에서 지운 행은 지우지 않고 DELETED_AT_SOURCE로 표시한다(결정 U7). 전체 읽기와
 * CDC 이벤트가 겹칠 때의 판단은 {@link ObjectSyncRepository} 주석과 decisions/2026-11-05-cdc-initial-load.md를
 * 따른다.
 */
@Service
@RequiredArgsConstructor
public class ObjectSyncService {

    private final ObjectSyncRepository repository;

    /** CDC 추가·수정 이벤트. 쓴 객체 ID, 쓰지 않았으면(이미 적재값에 있음·재전송) 빈 값. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> applyChange(SyncedObject object, SourceChange change) {
        return applyChange(object, change, Set.of());
    }

    /**
     * CDC 추가·수정 이벤트. 값을 받지 못한 속성(변경되지 않은 TOAST 값)은 기존 값을 유지한다(결정 문서 10.2). 객체가 아직 없으면 그 속성 없이 만든다.
     * 그때는 부르는 쪽(198-5)이 그 테이블을 다시 읽기 대상으로 표시한다.
     *
     * @param keepPropertyCodes 기존 값을 유지할 속성 코드
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> applyChange(
            SyncedObject object, SourceChange change, Set<String> keepPropertyCodes) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(keepPropertyCodes, "keepPropertyCodes");
        return repository.upsertChange(object, change, keepPropertyCodes);
    }

    /** 그 매핑·원천 키의 객체가 있는가(상태 무관). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(long mappingId, Map<String, Object> sourceKey) {
        return repository.exists(mappingId, sourceKey);
    }

    /**
     * CDC 삭제 이벤트. 그 원천 행의 객체를 삭제 표시한다. 켜진 매핑 중 그 행의 객체가 아직 없는 매핑이 있으면 삭제 흔적도 남겨, 이 이벤트보다 먼저 읽고 늦게
     * 쓰는 전체 읽기가 그 행을 다시 만들지 않게 한다. 흔적을 남기는 동안 전체 읽기 쓰기와 줄을 선다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DeleteResult applyDelete(
            long sourceResourceId, Map<String, Object> sourceKey, SourceChange change) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(change, "change");
        repository.lockResourceForChange(sourceResourceId);
        List<Long> marked = repository.markDeletedByChange(sourceResourceId, sourceKey, change);
        if (!repository.hasMappingWithoutObject(sourceResourceId, sourceKey)) {
            return new DeleteResult(marked, false);
        }
        repository.upsertDeleteMark(sourceResourceId, sourceKey, change);
        return new DeleteResult(marked, true);
    }

    /** 전체 읽기에서 읽은 행 하나. 쓴 객체 ID, 쓰지 않았으면 빈 값. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> applyLoaded(SyncedObject object, FullLoad load) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(load, "load");
        repository.lockResourceForLoad(object.sourceResourceId());
        return repository.upsertLoaded(object, load);
    }

    /**
     * 전체 읽기를 끝낼 때 매핑마다 부른다. 이번 읽기에 나오지 않은 객체를 삭제 표시하고, 이 읽기가 반영한 과거의 삭제 흔적을 지운다. source_resource의
     * 끝난 세대(full_load_done_gen)를 올리는 일은 전체 읽기(197)가 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FinishResult finishLoad(long sourceResourceId, long mappingId, FullLoad load) {
        Objects.requireNonNull(load, "load");
        repository.lockResourceForLoad(sourceResourceId);
        int missing = repository.markMissing(sourceResourceId, mappingId, load);
        int marksCleared = repository.deleteMarksSeenBy(sourceResourceId, load);
        return new FinishResult(missing, marksCleared);
    }

    /**
     * @param markedObjectIds 삭제 표시한 객체 ID(연결 재계산 대상)
     * @param markRecorded 그 행의 객체가 없는 매핑이 있어 삭제 흔적을 남겼는지
     */
    public record DeleteResult(List<Long> markedObjectIds, boolean markRecorded) {

        public DeleteResult {
            markedObjectIds = List.copyOf(markedObjectIds);
        }

        /** 삭제 표시한 객체 수. */
        public int objectsMarked() {
            return markedObjectIds.size();
        }
    }

    /**
     * @param objectsMarkedMissing 이번 읽기에 나오지 않아 삭제 표시한 객체 수
     * @param marksCleared 지운 삭제 흔적 수
     */
    public record FinishResult(int objectsMarkedMissing, int marksCleared) {}
}
