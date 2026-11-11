package com.axcore.platform.check.repository;

import com.axcore.platform.check.dto.ReverificationComparisonResponse.Evidence;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 검사 표본과 문제별 검증 기록을 한 SQL에서 읽어 비교하므로 JdbcClient를 사용한다. */
@Repository
@RequiredArgsConstructor
public class ReverificationComparisonRepository {

    private final JdbcClient jdbc;

    public List<StoredRow> findEvidence(long runId) {
        return jdbc.sql(
                        """
                        with evidence as (
                            select m.code, r.link_mapping_id, (sample->>'objectInstanceId')::bigint as object_id,
                                   sample as detail, 0 as priority
                            from check_result r
                            join link_mapping m on m.id = r.link_mapping_id
                            cross join lateral jsonb_array_elements(r.sample_rows) sample
                            where r.check_run_id = :runId
                              and jsonb_typeof(sample->'objectInstanceId') = 'number'
                            union all
                            select m.code, m.id, i.object_instance_id, v.detail, 1
                            from issue_verification v
                            join issue i on i.id = v.issue_id
                            join link_mapping m on m.id = case
                                when jsonb_typeof(v.detail->'linkMappingId') = 'number'
                                then (v.detail->>'linkMappingId')::bigint end
                            where v.check_run_id = :runId and i.object_instance_id is not null
                        )
                        select distinct on (code, object_id)
                               code, link_mapping_id, object_id, detail->>'classification' as classification,
                               detail->>'rawValue' as raw_value, detail->>'transformedValue' as transformed_value,
                               detail->>'reason' as reason
                        from evidence
                        where detail->>'classification' in ('MATCHED', 'UNMATCHED', 'DUPLICATE', 'VIOLATION')
                        order by code, object_id, priority, link_mapping_id desc
                        """)
                .param("runId", runId)
                .query(
                        (rs, index) ->
                                new StoredRow(
                                        rs.getString("code"),
                                        rs.getLong("object_id"),
                                        new Evidence(
                                                rs.getLong("link_mapping_id"),
                                                rs.getString("classification"),
                                                rs.getString("raw_value"),
                                                rs.getString("transformed_value"),
                                                rs.getString("reason"))))
                .list();
    }

    public record StoredRow(String ruleCode, long objectInstanceId, Evidence evidence) {}
}
