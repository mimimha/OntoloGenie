package com.axcore.platform.source.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 원천 행 하나를 정규형 Java 값으로 푼 것. 전체 읽기(197)는 JDBC 값에서, CDC 소비자(198)는 Debezium 값에서 같은 모양으로 만든다. 그다음
 * 매핑·속성 변환은 두 경로가 같은 코드(SourceRowConverter)를 쓴다.
 *
 * <p>정규형 값: 정수 Long, numeric BigDecimal(NaN·무한대는 Double), 실수 Double, Boolean, date LocalDate, time
 * LocalTime, timestamp LocalDateTime, timestamptz OffsetDateTime(UTC), json·jsonb 읽은 JSON 값,
 * 문자열·uuid String.
 *
 * @param values 컬럼 → 정규형 값(null 포함)
 * @param keyTexts 컬럼 → {@link SourceKeyText} 형식 문자열(json·jsonb는 원천이 정규화한 텍스트). 값이 없으면 null. 원천 키로
 *     쓰는 컬럼은 반드시 있어야 한다
 */
public record SourceRow(Map<String, Object> values, Map<String, String> keyTexts) {

    public SourceRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        keyTexts = Collections.unmodifiableMap(new LinkedHashMap<>(keyTexts));
    }
}
