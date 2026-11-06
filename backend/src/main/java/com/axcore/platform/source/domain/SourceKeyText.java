package com.axcore.platform.source.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 원천 키 값을 문자열로 바꾸는 공용 형식(결정 문서 2026-11-05 9.7). 전체 읽기(197)는 JDBC 값을, CDC 소비자(198)는 Debezium 값을 먼저
 * 같은 Java 값으로 푼 뒤 이 형식으로 바꾼다. 객체와 삭제 흔적은 이 문자열로 만든 {@code source_key}를 jsonb로 그대로 비교하므로, 두 경로가 같은
 * 행에서 같은 문자열을 만들어야 같은 객체가 된다.
 *
 * <p>DB 세션 설정(TimeZone, DateStyle)이나 JVM 시간대에 따라 바뀌지 않는다.
 *
 * <table>
 *   <caption>값 형식</caption>
 *   <tr><th>Java 값(원천 형식)</th><th>문자열</th><th>예</th></tr>
 *   <tr><td>String(문자열·uuid 등)</td><td>그대로</td><td>{@code WO-0001}</td></tr>
 *   <tr><td>Long, Integer, BigInteger(정수)</td><td>십진수</td><td>{@code 12}</td></tr>
 *   <tr><td>BigDecimal(numeric)</td><td>지수 표기 없이, 원천 소수 자릿수 그대로</td><td>{@code 12.50}</td></tr>
 *   <tr><td>Double(실수)</td><td>유한하면 지수 표기 없이</td><td>{@code 1.5}</td></tr>
 *   <tr><td>Boolean</td><td>{@code true}/{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>LocalDate(date)</td><td>ISO 날짜</td><td>{@code 2026-11-06}</td></tr>
 *   <tr><td>LocalTime(time)</td><td>ISO 시각, 초는 늘, 소수 초는 있을 때만</td><td>{@code 09:00:00}</td></tr>
 *   <tr><td>LocalDateTime(timestamp)</td><td>ISO 날짜시각(시간대 없음)</td><td>{@code 2026-11-06T09:00:00}</td></tr>
 *   <tr><td>OffsetDateTime(timestamptz)</td><td>UTC 순간</td><td>{@code 2026-11-06T00:00:00Z}</td></tr>
 * </table>
 *
 * <p>값이 없거나(null) 유한하지 않은 실수(NaN, 무한대)는 키가 없는 것으로 본다(null). 그 행은 객체로 만들지 않는다.
 */
public final class SourceKeyText {

    private SourceKeyText() {}

    /**
     * @return 키 문자열. 키로 쓸 값이 없으면 null
     * @throws IllegalArgumentException 위 표에 없는 Java 값
     */
    public static String of(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Long number -> number.toString();
            case Integer number -> number.toString();
            case BigInteger number -> number.toString();
            case BigDecimal number -> number.toPlainString();
            case Double number ->
                    Double.isFinite(number) ? BigDecimal.valueOf(number).toPlainString() : null;
            case Boolean bool -> bool.toString();
            case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
            case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
            case LocalDateTime time -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time);
            case OffsetDateTime time -> time.toInstant().toString();
            default ->
                    throw new IllegalArgumentException(
                            "원천 키로 쓸 수 없는 값 형식입니다: " + value.getClass().getSimpleName());
        };
    }
}
