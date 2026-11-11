package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.dto.ModelGraphEdgeResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * API 042의 관계 유형별 연결 건수를 집계한다.
 *
 * <p>Entity가 없는 {@code object_link}는 DB 조회 규칙에 따라 기존 {@link RuleLinkRepository}와 {@link
 * ConfirmedLinkRepository}의 조회 방식인 {@code JdbcClient}를 사용한다.
 */
@Repository
@RequiredArgsConstructor
public class ModelGraphQueryRepository {

    private final JdbcClient jdbc;

    public List<ModelGraphEdgeResponse> findModelGraphEdges() {
        return jdbc.sql(
                        """
                        select t.id, t.source_type_id, t.target_type_id, t.code, t.name,
                               t.cardinality, count(l.id) as relation_count,
                               count(l.id) filter (where l.status = 'ACTIVE') as active_link_count
                        from link_type t
                        left join object_link l on l.link_type_id = t.id
                        group by t.id
                        order by t.id
                        """)
                .query(
                        (rs, row) ->
                                new ModelGraphEdgeResponse(
                                        rs.getLong("id"),
                                        rs.getLong("source_type_id"),
                                        rs.getLong("target_type_id"),
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        LinkCardinality.valueOf(rs.getString("cardinality")),
                                        rs.getLong("relation_count"),
                                        rs.getLong("active_link_count")))
                .list();
    }
}
