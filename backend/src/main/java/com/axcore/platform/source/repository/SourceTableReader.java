package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKeyText;
import com.axcore.platform.source.domain.SourceRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 PostgreSQL 테이블의 행을 전부 읽는다(전체 읽기, Jira 197). 원천에는 아무것도 쓰지 않는다.
 *
 * <p>모든 테이블을 REPEATABLE READ 읽기 전용 트랜잭션 하나에서 읽는다. 첫 문장에서 {@code pg_current_snapshot()}을 받으므로 그 스냅샷이
 * 곧 모든 SELECT가 본 스냅샷이다. CDC 이벤트와의 경계는 이 스냅샷으로 판단한다(2026-11-05 결정, V11).
 *
 * <p>값은 컬럼 형식에 맞는 Java 값으로 읽는다: 정수 Long, numeric BigDecimal(NaN·무한대는 Double), 실수 Double, boolean,
 * date LocalDate, time LocalTime, timestamp LocalDateTime, timestamptz OffsetDateTime, json·jsonb
 * 읽은 JSON 값, 그 밖은 문자열. 원천 키 컬럼은 그 값을 {@link SourceKeyText} 형식 문자열로도 만든다(json·jsonb 키는 원천이 정규화한
 * 텍스트). 세션 시간대에 따라 바뀌는 PostgreSQL 텍스트 출력은 키로 쓰지 않는다.
 */
@Repository
public class SourceTableReader {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 600;
    private static final int STATEMENT_TIMEOUT_MILLIS = 300_000;
    private static final int FETCH_SIZE = 1_000;

    private static final String SNAPSHOT_SQL =
            "select pg_current_snapshot()::text, (pg_current_wal_lsn() - '0/0'::pg_lsn)::bigint";

    // 카탈로그의 resource_key({스키마}.{이름})로 실제 표를 찾고 이름을 원천이 직접 따옴표 처리한다.
    private static final String RELATION_SQL =
            """
            select quote_ident(n.nspname) || '.' || quote_ident(c.relname)
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname || '.' || c.relname = ? and c.relkind in ('r', 'p', 'v', 'm')
            """;

    private final String sslMode;
    private final JsonMapper json;

    public SourceTableReader(
            @Value("${axcore.source-check.ssl-mode}") String sslMode, JsonMapper json) {
        this.sslMode = sslMode;
        this.json = json;
    }

    /**
     * 테이블들을 한 스냅샷으로 읽는다. 행이 maxRows를 넘으면 {@link TooManyRowsException}이다. 접속·읽기 실패는 {@link
     * SQLException}이다.
     */
    public Result read(
            SourceEndpoint endpoint, SourceAccount account, List<Table> tables, int maxRows)
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(endpoint.jdbcUrl(), properties(account))) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                String snapshot;
                long lsn;
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery(SNAPSHOT_SQL)) {
                    rs.next();
                    snapshot = rs.getString(1);
                    lsn = rs.getLong(2);
                }
                Map<Long, List<SourceRow>> rows = new HashMap<>();
                int total = 0;
                for (Table table : tables) {
                    List<SourceRow> tableRows = readTable(connection, table, maxRows - total);
                    total += tableRows.size();
                    rows.put(table.resourceId(), tableRows);
                }
                return new Result(snapshot, lsn, rows);
            } finally {
                connection.rollback();
            }
        }
    }

    private List<SourceRow> readTable(Connection connection, Table table, int remaining)
            throws SQLException {
        String relation = relation(connection, table.resourceKey());
        String sql =
                "select "
                        + table.columns().stream()
                                .map(SourceTableReader::quote)
                                .collect(Collectors.joining(", "))
                        + " from "
                        + relation;
        List<SourceRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    if (rows.size() >= remaining) {
                        throw new TooManyRowsException();
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    Map<String, String> keyTexts = new LinkedHashMap<>();
                    for (int i = 1; i <= table.columns().size(); i++) {
                        String column = table.columns().get(i - 1);
                        String typeName = meta.getColumnTypeName(i);
                        Object value = value(rs, i, typeName);
                        values.put(column, value);
                        if (table.keyColumns().contains(column)) {
                            keyTexts.put(column, keyText(rs, i, typeName, value));
                        }
                    }
                    rows.add(new SourceRow(values, keyTexts));
                }
            }
        }
        return rows;
    }

    private static String relation(Connection connection, String resourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELATION_SQL)) {
            statement.setString(1, resourceKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("relation not found", "42P01");
                }
                String relation = rs.getString(1);
                if (rs.next()) {
                    throw new SQLException("relation name is ambiguous", "42P09");
                }
                return relation;
            }
        }
    }

    private Object value(ResultSet rs, int index, String typeName) throws SQLException {
        Object value =
                switch (typeName) {
                    case "int2", "int4", "int8" -> rs.getLong(index);
                    case "numeric" -> numeric(rs.getString(index));
                    case "float4", "float8" -> rs.getDouble(index);
                    case "bool" -> rs.getBoolean(index);
                    case "date" -> rs.getObject(index, LocalDate.class);
                    case "time" -> rs.getObject(index, LocalTime.class);
                    case "timestamp" -> rs.getObject(index, LocalDateTime.class);
                    case "timestamptz" -> rs.getObject(index, OffsetDateTime.class);
                    case "json", "jsonb" -> {
                        String text = rs.getString(index);
                        yield text == null ? null : json.readValue(text, Object.class);
                    }
                    default -> rs.getString(index);
                };
        return rs.wasNull() ? null : value;
    }

    /**
     * numeric 텍스트를 값으로 바꾼다. {@code getBigDecimal}은 NaN·Infinity에서 예외(22003)를 던져 실행 전체가 실패하므로 쓰지
     * 않는다. NaN·무한대는 Double로 두고, 속성 변환이 그 속성만 뺀다.
     */
    private static Object numeric(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> new BigDecimal(text);
        };
    }

    private static String keyText(ResultSet rs, int index, String typeName, Object value)
            throws SQLException {
        if (value == null) {
            return null;
        }
        if (typeName.equals("json") || typeName.equals("jsonb")) {
            return rs.getString(index);
        }
        return SourceKeyText.of(value);
    }

    // 컬럼 이름은 카탈로그(source_column)에서 온 값이다. 큰따옴표를 겹쳐 식별자로만 쓰이게 한다.
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private Properties properties(SourceAccount account) {
        Properties properties = new Properties();
        properties.setProperty("user", account.username());
        properties.setProperty("password", account.password());
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        properties.setProperty("readOnly", "true");
        properties.setProperty("ApplicationName", "axcore-full-load");
        properties.setProperty(
                "options",
                "-c statement_timeout=%d -c default_transaction_read_only=on"
                        .formatted(STATEMENT_TIMEOUT_MILLIS));
        return properties;
    }

    /**
     * @param columns SELECT할 컬럼(카탈로그 이름)
     * @param keyColumns 키 문자열({@link SourceKeyText})도 만들 원천 키 컬럼
     */
    public record Table(
            long resourceId, String resourceKey, List<String> columns, Set<String> keyColumns) {

        public Table {
            columns = List.copyOf(columns);
            keyColumns = Set.copyOf(keyColumns);
        }
    }

    /**
     * @param snapshot 읽기에 쓴 스냅샷({@code xmin:xmax:xip,...})
     * @param lsn 읽기 시점의 원천 WAL 위치(참고값)
     * @param rows 원천 테이블 ID → 행
     */
    public record Result(String snapshot, long lsn, Map<Long, List<SourceRow>> rows) {}

    /** 한 번에 읽을 수 있는 행 수를 넘었다. */
    public static class TooManyRowsException extends RuntimeException {

        TooManyRowsException() {
            super("too many source rows");
        }
    }
}
