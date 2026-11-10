package com.axcore.platform.action.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Action 정의·실행이 가리키는 다른 표(permission, role_permission, agent_message, object_instance)를 확인한다. */
@Repository
@RequiredArgsConstructor
public class ActionReferenceRepository {

    private final JdbcClient jdbc;

    public boolean permissionExists(String code) {
        return jdbc.sql("select exists (select 1 from permission where code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    public boolean roleHasPermission(String roleCode, String permissionCode) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from role_permission rp
                            join role r on r.id = rp.role_id
                            join permission p on p.id = rp.permission_id
                            where r.code = :roleCode and p.code = :permissionCode)
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
    }

    /** 실행 대상 객체의 유형. 없는 객체면 비어 있다. 대상 객체 조회 범위 검사(S15P31C106-205)에 쓴다. */
    public Optional<Long> findObjectTypeId(long objectId) {
        return jdbc.sql("select object_type_id from object_instance where id = :id")
                .param("id", objectId)
                .query(Long.class)
                .optional();
    }

    /** Agent 실행의 agentMessageId가 실행자 본인 대화의 질문(USER) 메시지인가. */
    public boolean isQuestionOf(long agentMessageId, long accountId) {
        return jdbc.sql(
                        """
                        select exists (
                            select 1 from agent_message m
                            join agent_conversation c on c.id = m.conversation_id
                            where m.id = :messageId and m.sender = 'USER'
                              and c.account_id = :accountId)
                        """)
                .param("messageId", agentMessageId)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }
}
