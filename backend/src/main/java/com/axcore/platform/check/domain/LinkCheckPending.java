package com.axcore.platform.check.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 확인 대기(V12 {@code link_check_pending}, 결정 문서 2026-11-05 8장 ②). 원천 사이 규칙에서 SYNC·수동 검사가 찾은 미연결을 바로
 * issue로 만들지 않고 둔다. 그 사이 연결되거나 출발 객체가 지워지면 일시 불일치(TRANSIENT)로 닫고, 원천이 정상인 채로 대기 시간이 지나도 안 맞으면
 * issue로 올린다(PROMOTED).
 */
@Getter
@Entity
@Table(name = "link_check_pending")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkCheckPending {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_mapping_id", nullable = false)
    private Long linkMappingId;

    @Column(name = "source_object_id", nullable = false)
    private Long sourceObjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_check_run_id", nullable = false)
    private Long lastCheckRunId;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason")
    private CloseReason closeReason;

    @Column(name = "issue_id")
    private Long issueId;

    /** 처음 미연결로 본 출발 객체를 대기에 넣는다. */
    public static LinkCheckPending waiting(
            long linkMappingId, long sourceObjectId, long checkRunId, Instant now) {
        LinkCheckPending pending = new LinkCheckPending();
        pending.linkMappingId = linkMappingId;
        pending.sourceObjectId = sourceObjectId;
        pending.status = Status.WAITING;
        pending.firstSeenAt = Objects.requireNonNull(now, "now");
        pending.lastCheckRunId = checkRunId;
        return pending;
    }

    /** 이번 검사에서도 미연결이다. */
    public void seen(long checkRunId) {
        requireWaiting();
        this.lastCheckRunId = checkRunId;
    }

    /** 일시 불일치로 닫는다(연결됨·출발 객체 삭제·규칙 개정). */
    public void closeTransient(CloseReason reason, long checkRunId, Instant now) {
        requireWaiting();
        if (reason == CloseReason.PROMOTED) {
            throw new IllegalArgumentException("승격은 promote로 닫는다.");
        }
        this.status = Status.TRANSIENT;
        this.closeReason = reason;
        this.lastCheckRunId = checkRunId;
        this.closedAt = closedAt(now);
    }

    /** issue로 올린다. issue 등록과 같은 트랜잭션에서 부른다. */
    public void promote(long issueId, long checkRunId, Instant now) {
        requireWaiting();
        this.status = Status.PROMOTED;
        this.closeReason = CloseReason.PROMOTED;
        this.issueId = issueId;
        this.lastCheckRunId = checkRunId;
        this.closedAt = closedAt(now);
    }

    private void requireWaiting() {
        if (status != Status.WAITING) {
            throw new IllegalStateException("대기 중인 확인 대기만 바꿀 수 있습니다: " + id);
        }
    }

    // ck_link_check_pending_time: 닫은 시각은 처음 본 시각보다 앞설 수 없다.
    private Instant closedAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return now.isBefore(firstSeenAt) ? firstSeenAt : now;
    }

    public enum Status {
        WAITING,
        TRANSIENT,
        PROMOTED
    }

    public enum CloseReason {
        /** 미연결이 아니게 됨(연결됨, 또는 다른 판정으로 바뀜). */
        LINKED,
        /** 출발 객체가 원천에서 지워짐. */
        SOURCE_DELETED,
        /** 규칙 개정본이 더 이상 반영 중이 아님. */
        RULE_RETIRED,
        PROMOTED
    }
}
