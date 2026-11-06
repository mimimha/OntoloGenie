package com.axcore.platform.action.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 재배정 계획 (FN-ACT-06, API 093·094). 조회 전용이다. 저장은 실행 기록이 계획 ID를 먼저 알아야 하므로 {@code
 * ReassignmentPlanRepository}가 시퀀스에서 ID를 받아 SQL로 넣는다. 한 번 만든 계획은 고치지 않으므로 {@link Immutable}로 두고 바꾸는
 * 메서드를 두지 않는다. status는 CREATED뿐이며 원천 MES에 적용됐다는 뜻이 아니다.
 */
@Getter
@Entity
@Immutable
@Table(name = "reassignment_plan")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReassignmentPlan {

    @Id private Long id;

    @Column(nullable = false)
    private String status;

    /** 정지 설비(object_instance). */
    @Column(nullable = false)
    private Long fromEquipmentId;

    /** 대체 설비 선택 근거. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> selectionBasis;

    /** 계획에 쓴 데이터의 기준 시점. */
    @Column(nullable = false)
    private Instant dataAsOf;

    /** 계획을 만든 실행 기록(API 092). */
    @Column(nullable = false)
    private Long actionExecutionId;

    /** Agent 대화에서 만들었을 때만 있다. */
    private Long agentConversationId;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private Instant createdAt;
}
