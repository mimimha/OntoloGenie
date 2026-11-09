package com.axcore.platform.source.service;

import com.axcore.platform.source.domain.SourceKeyText;
import com.axcore.platform.source.domain.SourceRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Debezium 변경 이벤트의 행(before·after)을 정규형 값의 {@link SourceRow}로 푼다(Jira 198-1). 전체 읽기(197)가 JDBC 값에서
 * 만드는 것과 같은 값·같은 원천 키 문자열을 만들어야 한다. 두 경로를 같은 원천 행으로 비교하는 시험이 이를 지킨다
 * (DebeziumRowDecoderParityTests).
 *
 * <p>커넥터 설정(infra/cdc/connectors)에 맞춘다: {@code decimal.handling.mode=string}, {@code
 * time.precision.mode} 기본값(adaptive), JSON 변환기의 스키마 포함.
 *
 * <table>
 *   <caption>Debezium 값 → 정규형 값</caption>
 *   <tr><th>스키마</th><th>Debezium 값</th><th>정규형</th></tr>
 *   <tr><td>int16·int32·int64</td><td>정수</td><td>Long</td></tr>
 *   <tr><td>float·double</td><td>실수</td><td>Double</td></tr>
 *   <tr><td>boolean</td><td>참거짓</td><td>Boolean</td></tr>
 *   <tr><td>string, 원천 형식 numeric</td><td>숫자 문자열, NaN은 {@code NAN}</td><td>BigDecimal(NaN·무한대는 Double)</td></tr>
 *   <tr><td>string(그 밖), io.debezium.data.Uuid</td><td>문자열</td><td>String</td></tr>
 *   <tr><td>io.debezium.time.Date</td><td>1970-01-01부터 일수</td><td>LocalDate</td></tr>
 *   <tr><td>io.debezium.time.Time·MicroTime·NanoTime</td><td>자정부터 ms·µs·ns</td><td>LocalTime</td></tr>
 *   <tr><td>io.debezium.time.Timestamp·MicroTimestamp·NanoTimestamp</td><td>1970-01-01T00:00부터 ms·µs·ns(시간대 없음)</td><td>LocalDateTime</td></tr>
 *   <tr><td>io.debezium.time.ZonedTimestamp</td><td>ISO 문자열(UTC)</td><td>OffsetDateTime(UTC)</td></tr>
 *   <tr><td>io.debezium.data.Json</td><td>원천이 정규화한 JSON 텍스트</td><td>읽은 JSON 값(원천 키 문자열은 텍스트 그대로)</td></tr>
 * </table>
 *
 * <p>numeric은 {@code decimal.handling.mode=string}이라 스키마로는 문자열 컬럼과 구별되지 않는다. 그래서 카탈로그의 원천 자료형
 * ({@code source_column.source_data_type})을 같이 받는다. 위 표에 없는 형식(bytea, 배열, timetz, interval 등)은
 * {@link UnsupportedValueException}이다. 변경되지 않은 TOAST 값({@value #UNAVAILABLE})은 값에 넣지 않고 따로 알린다.
 */
@Component
public class DebeziumRowDecoder {

    /** REPLICA IDENTITY DEFAULT에서 변경되지 않은 TOAST 값 자리에 오는 표시. */
    public static final String UNAVAILABLE = "__debezium_unavailable_value";

    private final JsonMapper json;

    public DebeziumRowDecoder(JsonMapper json) {
        this.json = json;
    }

    /**
     * 메시지 스키마에서 행 구조(after 필드)의 컬럼별 형식을 꺼낸다.
     *
     * @param messageSchema 메시지의 {@code schema}(봉투 구조)
     */
    public static Map<String, Field> rowFields(JsonNode messageSchema) {
        for (JsonNode envelopeField : messageSchema.path("fields")) {
            if ("after".equals(envelopeField.path("field").asString())) {
                Map<String, Field> fields = new LinkedHashMap<>();
                for (JsonNode column : envelopeField.path("fields")) {
                    fields.put(
                            column.path("field").asString(),
                            new Field(
                                    column.path("type").asString(),
                                    column.hasNonNull("name")
                                            ? column.get("name").asString()
                                            : null));
                }
                return fields;
            }
        }
        throw new IllegalArgumentException("메시지 스키마에 after 행 구조가 없습니다.");
    }

    /**
     * @param fields {@link #rowFields}로 꺼낸 컬럼별 형식
     * @param row 이벤트의 before 또는 after
     * @param sourceTypes 컬럼 → 카탈로그 원천 자료형(예: {@code numeric(12,2)}). 없는 컬럼은 문자열로 본다
     */
    public Decoded decode(
            Map<String, Field> fields, JsonNode row, Map<String, String> sourceTypes) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> keyTexts = new LinkedHashMap<>();
        Set<String> unavailable = new LinkedHashSet<>();
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String column = entry.getKey();
            JsonNode node = row.get(column);
            if (node == null || node.isNull()) {
                values.put(column, null);
                keyTexts.put(column, null);
                continue;
            }
            if (node.isString() && UNAVAILABLE.equals(node.asString())) {
                unavailable.add(column);
                continue;
            }
            Field field = entry.getValue();
            Object value = value(column, field, node, sourceTypes.get(column));
            values.put(column, value);
            keyTexts.put(
                    column,
                    Field.JSON.equals(field.name()) ? node.asString() : SourceKeyText.of(value));
        }
        return new Decoded(
                new SourceRow(values, keyTexts), Collections.unmodifiableSet(unavailable));
    }

    private Object value(String column, Field field, JsonNode node, String sourceType) {
        if (field.name() != null) {
            switch (field.name()) {
                case "io.debezium.time.Date":
                    return LocalDate.ofEpochDay(node.asLong());
                case "io.debezium.time.Time":
                    return LocalTime.ofNanoOfDay(node.asLong() * 1_000_000L);
                case "io.debezium.time.MicroTime":
                    return LocalTime.ofNanoOfDay(node.asLong() * 1_000L);
                case "io.debezium.time.NanoTime":
                    return LocalTime.ofNanoOfDay(node.asLong());
                case "io.debezium.time.Timestamp":
                    return fromEpoch(node.asLong(), 1_000L);
                case "io.debezium.time.MicroTimestamp":
                    return fromEpoch(node.asLong(), 1_000_000L);
                case "io.debezium.time.NanoTimestamp":
                    return fromEpoch(node.asLong(), 1_000_000_000L);
                case "io.debezium.time.ZonedTimestamp":
                    return OffsetDateTime.parse(node.asString())
                            .withOffsetSameInstant(ZoneOffset.UTC);
                case Field.JSON:
                    return json.readValue(node.asString(), Object.class);
                case "io.debezium.data.Uuid":
                    return node.asString();
                default:
                    throw new UnsupportedValueException(column, field.name());
            }
        }
        return switch (field.type()) {
            case "int8", "int16", "int32", "int64" -> node.asLong();
            case "float", "double", "float32", "float64" ->
                    node.isNumber() ? node.asDouble() : special(node.asString(), column, field);
            case "boolean" -> node.asBoolean();
            case "string" -> isNumeric(sourceType) ? numeric(node.asString()) : node.asString();
            default -> throw new UnsupportedValueException(column, field.type());
        };
    }

    // 1970-01-01T00:00부터의 단위 수를 시간대 없는 날짜시각으로. 음수(1970년 이전)도 맞게 나눈다.
    private static LocalDateTime fromEpoch(long amount, long unitsPerSecond) {
        long seconds = Math.floorDiv(amount, unitsPerSecond);
        long nanos = Math.floorMod(amount, unitsPerSecond) * (1_000_000_000L / unitsPerSecond);
        return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC);
    }

    private static boolean isNumeric(String sourceType) {
        return sourceType != null && sourceType.toLowerCase(Locale.ROOT).startsWith("numeric");
    }

    // JDBC 경로(SourceTableReader)와 같이 NaN·무한대는 Double로 둔다. Debezium은 NaN을 "NAN"으로 보낸다.
    private static Object numeric(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "nan" -> Double.NaN;
            case "infinity" -> Double.POSITIVE_INFINITY;
            case "-infinity" -> Double.NEGATIVE_INFINITY;
            default -> new BigDecimal(text);
        };
    }

    private static Double special(String text, String column, Field field) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "nan" -> Double.NaN;
            case "infinity" -> Double.POSITIVE_INFINITY;
            case "-infinity" -> Double.NEGATIVE_INFINITY;
            default -> throw new UnsupportedValueException(column, field.type());
        };
    }

    /**
     * @param type Kafka Connect 기본 형식(int32, string 등)
     * @param name 논리 형식 이름(io.debezium.time.Date 등). 없으면 null
     */
    public record Field(String type, String name) {

        static final String JSON = "io.debezium.data.Json";
    }

    /**
     * @param row 정규형 값과 원천 키 문자열
     * @param unavailableColumns 변경되지 않은 TOAST 값이라 값을 받지 못한 컬럼. 기존 값을 덮어쓰면 안 된다
     */
    public record Decoded(SourceRow row, Set<String> unavailableColumns) {}

    /** 지원하지 않는 원천 형식. 값은 메시지에 넣지 않는다. */
    public static class UnsupportedValueException extends RuntimeException {

        public UnsupportedValueException(String column, String type) {
            super("지원하지 않는 원천 값 형식입니다: %s(%s)".formatted(column, type));
        }
    }
}
