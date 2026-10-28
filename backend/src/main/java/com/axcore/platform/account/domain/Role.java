package com.axcore.platform.account.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 역할. 마이그레이션이 넣은 고정 데이터만 쓰고 애플리케이션에서 만들거나 고치지 않는다. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    @Id private Long id;

    @Enumerated(EnumType.STRING)
    private RoleCode code;

    private String name;
}
