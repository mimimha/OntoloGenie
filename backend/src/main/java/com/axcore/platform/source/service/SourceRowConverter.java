package com.axcore.platform.source.service;

import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.service.ValueTransformer;
import com.axcore.platform.check.service.ValueTransformer.TransformException;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.SourceRow;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 행 하나를 매핑대로 객체 값({@link SyncedObject})으로 바꾼다(Jira 197).
 *
 * <ul>
 *   <li>원천 키: 키 컬럼 값을 {@link com.axcore.platform.source.domain.SourceKeyText} 형식 문자열로 넣는다. CDC
 *       소비자(198)도 같은 형식을 써야 같은 객체가 된다. 키 값이 비어 있는 행은 객체로 만들지 않는다.
 *   <li>속성: 변환 단계가 있으면 값을 문자열로 바꿔 변환한 뒤 속성 자료형으로 바꾼다. 없으면 읽은 값을 바로 자료형으로 바꾼다. 형식은 {@link
 *       PropertyDataType} 설명을 따른다. 바꾸지 못한 값은 그 속성만 빼고 개수만 센다(원천 값은 남기지 않는다).
 *   <li>시간대 없는 timestamp는 {@code axcore.source-load.zone-id}의 시각으로 본다.
 *   <li>표시 이름: 객체 유형의 대표 속성 값, 없으면 원천 키 값을 " / "로 이은 것. 300자(코드 포인트)를 넘으면 자른다. 넘는 행 하나 때문에 실행 전체의
 *       쓰기가 롤백되지 않게 한다.
 * </ul>
 */
@Component
public class SourceRowConverter {

    /** {@code object_instance.display_name varchar(300)}. */
    static final int DISPLAY_NAME_MAX_LENGTH = 300;

    private final ValueTransformer transformer;
    private final JsonMapper json;
    private final ZoneId zone;

    public SourceRowConverter(
            ValueTransformer transformer,
            JsonMapper json,
            @Value("${axcore.source-load.zone-id}") String zoneId) {
        this.transformer = transformer;
        this.json = json;
        this.zone = ZoneId.of(zoneId);
    }

    /** 원천 키가 비어 있으면 object가 null이다. */
    public Conversion convert(long resourceId, MappingPlan mapping, SourceRow row) {
        Map<String, Object> sourceKey = new LinkedHashMap<>();
        for (String column : mapping.keyColumns()) {
            String text = row.keyTexts().get(column);
            if (text == null) {
                return new Conversion(null, 0);
            }
            sourceKey.put(column, text);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        int failedValues = 0;
        for (PropertyPlan property : mapping.properties()) {
            try {
                properties.put(
                        property.propertyCode(),
                        propertyValue(
                                row.values().get(property.columnName()),
                                property.steps(),
                                property.dataType()));
            } catch (ConversionException e) {
                failedValues++;
            }
        }
        Object display =
                mapping.displayPropertyCode() == null
                        ? null
                        : properties.get(mapping.displayPropertyCode());
        String displayName = display == null ? "" : text(display);
        if (displayName.isBlank()) {
            displayName =
                    String.join(" / ", sourceKey.values().stream().map(String::valueOf).toList());
        }
        if (displayName.isBlank()) {
            displayName = json.writeValueAsString(sourceKey);
        }
        displayName = truncate(displayName, DISPLAY_NAME_MAX_LENGTH);
        return new Conversion(
                new SyncedObject(
                        resourceId,
                        mapping.mappingId(),
                        mapping.objectTypeId(),
                        sourceKey,
                        displayName,
                        properties),
                failedValues);
    }

    // PostgreSQL varchar(n)은 문자(코드 포인트) 수로 센다. 서로게이트 쌍을 가르지 않는다.
    static String truncate(String text, int maxLength) {
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength));
    }

    Object propertyValue(Object value, List<TransformStep> steps, PropertyDataType type) {
        if (value == null) {
            return null;
        }
        Object input = value;
        if (!steps.isEmpty()) {
            try {
                input = transformer.transform(text(value), steps);
            } catch (TransformException | ClassCastException e) {
                throw new ConversionException();
            }
            if (input == null) {
                return null;
            }
        }
        Object result = coerce(input, type);
        if (!type.accepts(result)) {
            throw new ConversionException();
        }
        return result;
    }

    private Object coerce(Object value, PropertyDataType type) {
        try {
            return switch (type) {
                case STRING -> text(value);
                case INTEGER -> integer(value);
                case DECIMAL -> decimal(value).toPlainString();
                case DATE ->
                        switch (value) {
                            case LocalDate date -> date.toString();
                            case String text -> LocalDate.parse(text.strip()).toString();
                            default -> throw new ConversionException();
                        };
                case TIME ->
                        switch (value) {
                            case LocalTime time -> time.toString();
                            case String text -> LocalTime.parse(text.strip()).toString();
                            default -> throw new ConversionException();
                        };
                case DATETIME -> dateTime(value).toString();
                case BOOLEAN ->
                        switch (value) {
                            case Boolean bool -> bool;
                            case String text when text.strip().equalsIgnoreCase("true") -> true;
                            case String text when text.strip().equalsIgnoreCase("false") -> false;
                            default -> throw new ConversionException();
                        };
                case JSON -> jsonValue(value);
            };
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException e) {
            throw new ConversionException();
        }
    }

    private static Object integer(Object value) {
        BigInteger integer =
                switch (value) {
                    case Long number -> BigInteger.valueOf(number);
                    case BigDecimal number -> number.stripTrailingZeros().toBigIntegerExact();
                    case Double number -> BigDecimal.valueOf(number).toBigIntegerExact();
                    case String text -> new BigInteger(text.strip());
                    default -> throw new ConversionException();
                };
        return integer.bitLength() < Long.SIZE ? (Object) integer.longValueExact() : integer;
    }

    private static BigDecimal decimal(Object value) {
        return switch (value) {
            case Long number -> BigDecimal.valueOf(number);
            case BigDecimal number -> number;
            case Double number when Double.isFinite(number) -> BigDecimal.valueOf(number);
            case String text -> new BigDecimal(text.strip());
            default -> throw new ConversionException();
        };
    }

    private OffsetDateTime dateTime(Object value) {
        return switch (value) {
            case OffsetDateTime time -> time;
            case LocalDateTime time -> time.atZone(zone).toOffsetDateTime();
            case String text -> {
                String trimmed = text.strip();
                try {
                    yield OffsetDateTime.parse(trimmed);
                } catch (DateTimeParseException e) {
                    yield LocalDateTime.parse(trimmed).atZone(zone).toOffsetDateTime();
                }
            }
            default -> throw new ConversionException();
        };
    }

    private Object jsonValue(Object value) {
        return switch (value) {
            case Double number when !Double.isFinite(number) -> throw new ConversionException();
            case BigDecimal number -> number.toPlainString();
            case LocalDate date -> date.toString();
            case LocalTime time -> time.toString();
            case LocalDateTime time -> time.atZone(zone).toOffsetDateTime().toString();
            case OffsetDateTime time -> time.toString();
            default -> value;
        };
    }

    /** 변환 단계와 STRING 속성에 넘길 문자열. 날짜·시각은 ISO 8601, 숫자는 지수 표기 없이, JSON은 JSON 문자열이다. */
    private String text(Object value) {
        return switch (value) {
            case String text -> text;
            case BigDecimal number -> number.toPlainString();
            case Double number when Double.isFinite(number) ->
                    BigDecimal.valueOf(number).toPlainString();
            case Map<?, ?> map -> json.writeValueAsString(map);
            case List<?> list -> json.writeValueAsString(list);
            default -> String.valueOf(value);
        };
    }

    /**
     * @param object 만든 객체 값. 원천 키가 비어 있어 만들지 못했으면 null
     * @param failedValues 자료형으로 바꾸지 못해 뺀 속성 수
     */
    public record Conversion(SyncedObject object, int failedValues) {}

    /** 값을 속성 자료형으로 바꾸지 못했다. 원천 값을 담지 않는다. */
    static class ConversionException extends RuntimeException {

        ConversionException() {
            super("value conversion failed", null, false, false);
        }
    }
}
