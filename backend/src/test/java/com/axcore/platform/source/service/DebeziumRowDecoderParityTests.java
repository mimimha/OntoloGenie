package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.check.service.ValueTransformer;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.SyncedObject;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import com.axcore.platform.source.service.DebeziumRowDecoder.Decoded;
import com.axcore.platform.source.service.DebeziumRowDecoder.Field;
import com.axcore.platform.source.service.DebeziumRowDecoder.UnsupportedValueException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 원천 행을 197(JDBC 전체 읽기)과 198(Debezium 이벤트)로 넣었을 때 같은 원천 키·속성 값이 나오는지 본다(Jira 198-1, 결정 문서 9.7).
 *
 * <p>원천 행은 {@code cdc-parity/typed.sql}이다. Debezium 쪽은 같은 SQL을 Debezium 3.6.3(서버와 같은 커넥터 설정, 원천 토픽
 * 하나)에 흘려 받은 실제 메시지 {@code cdc-parity/debezium-typed-events.jsonl}이다. SQL을 고치면 메시지도 다시 받아야 한다.
 */
class DebeziumRowDecoderParityTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, List<String>> KEYS =
            Map.of(
                    "typed", List.of("id"),
                    "line", List.of("order_no", "line_no"),
                    "by_day", List.of("day"),
                    "by_instant", List.of("at"),
                    "by_text", List.of("code"));

    private static PostgreSQLContainer postgres;
    private static Map<String, Map<String, String>> sourceTypes;
    private static Map<String, List<String>> columns;
    private static Map<String, List<SourceRow>> jdbcRows;
    private static Map<String, List<Decoded>> debeziumRows;

    private final SourceRowConverter converter =
            new SourceRowConverter(new ValueTransformer(), JSON, "Asia/Seoul");

    @BeforeAll
    static void loadBothPaths() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
        postgres.start();
        try (Connection connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(resource("cdc-parity/typed.sql"));
            readCatalog(connection);
        }

        SourceTableReader reader = new SourceTableReader("disable", JSON);
        List<Table> tables = new ArrayList<>();
        Map<Long, String> tableIds = new HashMap<>();
        long id = 1;
        for (String table : KEYS.keySet()) {
            tables.add(
                    new Table(id, "erp." + table, columns.get(table), Set.copyOf(KEYS.get(table))));
            tableIds.put(id++, table);
        }
        SourceTableReader.Result read =
                reader.read(
                        SourceEndpoint.from(
                                Map.of(
                                        "host",
                                        postgres.getHost(),
                                        "port",
                                        postgres.getMappedPort(5432),
                                        "database",
                                        postgres.getDatabaseName())),
                        new SourceAccount(postgres.getUsername(), postgres.getPassword()),
                        tables,
                        1_000);
        jdbcRows = new HashMap<>();
        read.rows().forEach((tableId, rows) -> jdbcRows.put(tableIds.get(tableId), rows));

        DebeziumRowDecoder decoder = new DebeziumRowDecoder(JSON);
        debeziumRows = new HashMap<>();
        for (String line : resource("cdc-parity/debezium-typed-events.jsonl").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = JSON.readTree(line);
            JsonNode payload = message.get("payload");
            String table = payload.path("source").path("table").asString();
            Map<String, Field> fields = DebeziumRowDecoder.rowFields(message.get("schema"));
            debeziumRows
                    .computeIfAbsent(table, t -> new ArrayList<>())
                    .add(decoder.decode(fields, payload.get("after"), sourceTypes.get(table)));
        }
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void bothPathsProduceSameValuesAndKeyTexts() {
        assertThat(debeziumRows.keySet()).isEqualTo(KEYS.keySet());
        for (String table : KEYS.keySet()) {
            Map<String, SourceRow> jdbc = byKey(table, jdbcRows.get(table));
            Map<String, SourceRow> debezium =
                    byKey(table, debeziumRows.get(table).stream().map(Decoded::row).toList());
            assertThat(debezium.keySet()).as(table).isEqualTo(jdbc.keySet());
            for (String key : jdbc.keySet()) {
                SourceRow expected = jdbc.get(key);
                SourceRow actual = debezium.get(key);
                assertThat(actual.values()).as(table + " " + key).isEqualTo(expected.values());
                for (String column : KEYS.get(table)) {
                    assertThat(actual.keyTexts().get(column))
                            .as(table + " " + key + " " + column)
                            .isEqualTo(expected.keyTexts().get(column));
                }
            }
        }
    }

    @Test
    void keyTextsUseSharedFormat() {
        assertThat(keyTexts("by_instant", "at"))
                .containsExactlyInAnyOrder("2026-11-06T00:00:00Z", "2026-11-06T00:00:00.000001Z");
        assertThat(keyTexts("by_day", "day")).containsExactlyInAnyOrder("2026-11-06", "1969-12-31");
        assertThat(keyTexts("line", "line_no")).containsExactlyInAnyOrder("3", "10");
        assertThat(keyTexts("by_text", "code")).containsExactlyInAnyOrder("WO-0001", "설비 A");
    }

    @Test
    void sameObjectsThroughConverterForEveryPropertyType() {
        for (PropertyDataType type : PropertyDataType.values()) {
            MappingPlan mapping = allColumnsAs("typed", type);
            Map<String, SourceRow> jdbc = byKey("typed", jdbcRows.get("typed"));
            Map<String, SourceRow> debezium =
                    byKey("typed", debeziumRows.get("typed").stream().map(Decoded::row).toList());
            for (String key : jdbc.keySet()) {
                SourceRowConverter.Conversion expected =
                        converter.convert(1, mapping, jdbc.get(key));
                SourceRowConverter.Conversion actual =
                        converter.convert(1, mapping, debezium.get(key));
                assertThat(actual.failedValues())
                        .as(type + " " + key)
                        .isEqualTo(expected.failedValues());
                SyncedObject expectedObject = expected.object();
                SyncedObject actualObject = actual.object();
                assertThat(actualObject).as(type + " " + key).isEqualTo(expectedObject);
            }
        }
    }

    @Test
    void unchangedToastValueIsReportedNotDecoded() {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("id", new Field("int64", null));
        fields.put("big", new Field("string", null));
        JsonNode row = JSON.readTree("{\"id\": 1, \"big\": \"__debezium_unavailable_value\"}");

        Decoded decoded = new DebeziumRowDecoder(JSON).decode(fields, row, Map.of());

        assertThat(decoded.unavailableColumns()).containsExactly("big");
        assertThat(decoded.row().values()).containsOnlyKeys("id");
    }

    @Test
    void rejectsUnsupportedTypes() {
        Map<String, Field> fields = Map.of("raw", new Field("bytes", null));
        JsonNode row = JSON.readTree("{\"raw\": \"AQI=\"}");

        assertThatThrownBy(() -> new DebeziumRowDecoder(JSON).decode(fields, row, Map.of()))
                .isInstanceOf(UnsupportedValueException.class)
                .hasMessageNotContaining("AQI=");
    }

    private static List<String> keyTexts(String table, String column) {
        return debeziumRows.get(table).stream().map(d -> d.row().keyTexts().get(column)).toList();
    }

    private static Map<String, SourceRow> byKey(String table, List<SourceRow> rows) {
        Map<String, SourceRow> byKey = new LinkedHashMap<>();
        for (SourceRow row : rows) {
            String key =
                    String.join(
                            "|", KEYS.get(table).stream().map(c -> row.keyTexts().get(c)).toList());
            assertThat(byKey.put(key, row)).as("중복 키 " + table + " " + key).isNull();
        }
        return byKey;
    }

    private static MappingPlan allColumnsAs(String table, PropertyDataType type) {
        List<PropertyPlan> properties = new ArrayList<>();
        long id = 1;
        for (String column : columns.get(table)) {
            properties.add(new PropertyPlan(id, 0, id++, column, type, 0, column, List.of()));
        }
        return new MappingPlan(1, 0, 1, 0, KEYS.get(table), "code", properties);
    }

    private static void readCatalog(Connection connection) throws SQLException {
        sourceTypes = new HashMap<>();
        columns = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                """
                                select c.relname, a.attname, format_type(a.atttypid, a.atttypmod)
                                from pg_attribute a
                                join pg_class c on c.oid = a.attrelid
                                join pg_namespace n on n.oid = c.relnamespace
                                where n.nspname = 'erp' and c.relkind = 'r' and a.attnum > 0
                                  and not a.attisdropped
                                order by c.relname, a.attnum
                                """)) {
            while (rs.next()) {
                sourceTypes
                        .computeIfAbsent(rs.getString(1), t -> new HashMap<>())
                        .put(rs.getString(2), rs.getString(3));
                columns.computeIfAbsent(rs.getString(1), t -> new ArrayList<>())
                        .add(rs.getString(2));
            }
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in =
                DebeziumRowDecoderParityTests.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
