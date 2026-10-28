package com.axcore.platform.global.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.config.JpaAuditingConfig;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 공통 Entity를 상속하면 생성·수정 시각과 계정이 실제 테이블(object_type)에 채워진다. */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    JpaAuditingConfig.class,
    JpaAuditingTests.FixedClockConfig.class
})
class JpaAuditingTests {

    private static final Instant CREATED = Instant.parse("2026-10-28T03:00:00Z");

    @Autowired EntityManager entityManager;

    @Autowired JdbcClient jdbcClient;

    @Autowired MutableClock clock;

    // 로그인 사용자를 알려 주는 AuditorAware는 로그인 기능(S15P31C106-201)에서 만든다.
    @MockitoBean AuditorAware<Long> auditorAware;

    @Test
    void fillsTimesAndAccountOnInsert() {
        Long accountId = insertAccount("auditor");
        given(auditorAware.getCurrentAuditor()).willReturn(Optional.of(accountId));

        SampleEntity entity = persist();

        assertThat(entity.getCreatedAt()).isEqualTo(CREATED);
        assertThat(entity.getUpdatedAt()).isEqualTo(CREATED);
        assertThat(entity.getCreatedBy()).isEqualTo(accountId);
        assertThat(entity.getUpdatedBy()).isEqualTo(accountId);
        assertThat(storedCreatedAt(entity.getId())).isEqualTo(CREATED);
    }

    @Test
    void changesOnlyUpdatedFieldsOnUpdate() {
        Long creator = insertAccount("creator");
        Long editor = insertAccount("editor");
        given(auditorAware.getCurrentAuditor()).willReturn(Optional.of(creator));
        SampleEntity entity = persist();

        clock.advance(Duration.ofMinutes(5));
        given(auditorAware.getCurrentAuditor()).willReturn(Optional.of(editor));
        entity.rename("설비 장치");
        entityManager.flush();

        assertThat(entity.getCreatedAt()).isEqualTo(CREATED);
        assertThat(entity.getCreatedBy()).isEqualTo(creator);
        assertThat(entity.getUpdatedAt()).isEqualTo(CREATED.plus(Duration.ofMinutes(5)));
        assertThat(entity.getUpdatedBy()).isEqualTo(editor);
    }

    @Test
    void leavesAccountEmptyWithoutLoginUser() {
        given(auditorAware.getCurrentAuditor()).willReturn(Optional.empty());

        SampleEntity entity = persist();

        assertThat(entity.getCreatedAt()).isEqualTo(CREATED);
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
    }

    private SampleEntity persist() {
        SampleEntity entity = new SampleEntity("EQUIPMENT", "설비");
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private Long insertAccount(String loginId) {
        return jdbcClient
                .sql(
                        """
                        insert into account (login_id, display_name, password_hash, role_id)
                        values (:loginId, :loginId, 'hash',
                                (select id from role where code = 'VIEWER'))
                        returning id
                        """)
                .param("loginId", loginId)
                .query(Long.class)
                .single();
    }

    private Instant storedCreatedAt(Long id) {
        return jdbcClient
                .sql("select created_at from object_type where id = :id")
                .param("id", id)
                .query(Instant.class)
                .single();
    }

    /** 공통 Entity 검증용. 필수 컬럼만 매핑하고 나머지는 DB 기본값을 쓴다. */
    @Getter
    @Entity
    @Table(name = "object_type")
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    static class SampleEntity extends BaseAuditEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String code;

        private String name;

        SampleEntity(String code, String name) {
            this.code = code;
            this.name = name;
        }

        void rename(String name) {
            this.name = name;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(CREATED);
        }
    }

    /** 수정 시각을 확인하려고 시각을 앞으로 옮길 수 있는 Clock. */
    static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
