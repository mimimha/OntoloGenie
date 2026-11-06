package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 조회 도구 호출 한 건의 결과. 답변의 answerMeta.results[] 한 줄이 된다(API 084). 결과 유형은 서버가 정하고 AI가 추측하지 않는다.
 *
 * @param resultCategory 조회가 끝났으면(SUCCEEDED) 필수, 실패(FAILED)면 null
 * @param dataAsOf 기준 시점. 알 수 없으면 null이고 다른 시각으로 채우지 않는다(정책 문서 4장)
 */
public record AgentQueryResult(
        String label,
        String toolCode,
        boolean succeeded,
        AgentResultCategory resultCategory,
        List<Map<String, Object>> sources,
        Instant dataAsOf,
        boolean estimated,
        String errorMessage) {

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("toolCode", toolCode);
        result.put("status", succeeded ? "SUCCEEDED" : "FAILED");
        result.put("resultCategory", resultCategory == null ? null : resultCategory.name());
        result.put("sources", sources);
        result.put("dataAsOf", dataAsOf == null ? null : dataAsOf.toString());
        result.put("estimated", estimated);
        result.put("errorMessage", errorMessage);
        return result;
    }
}
