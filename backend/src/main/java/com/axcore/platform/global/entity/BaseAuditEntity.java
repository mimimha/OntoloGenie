package com.axcore.platform.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/**
 * created_by, updated_by 컬럼도 가진 테이블의 Entity가 상속한다. 값은 로그인한 계정 ID(account.id)다. 로그인 사용자가 없는 작업(배치,
 * AI 제안)에서는 NULL로 남는다.
 */
@Getter
@MappedSuperclass
public abstract class BaseAuditEntity extends BaseTimeEntity {

    @CreatedBy
    @Column(updatable = false)
    private Long createdBy;

    @LastModifiedBy private Long updatedBy;
}
