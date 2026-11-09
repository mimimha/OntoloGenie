package com.axcore.platform.source.cdc;

import com.axcore.platform.source.cdc.CdcMessage.BoundaryStatus;
import com.axcore.platform.source.cdc.CdcMessage.ChangeEvent;
import com.axcore.platform.source.cdc.CdcMessage.Heartbeat;
import com.axcore.platform.source.cdc.CdcMessage.Operation;
import com.axcore.platform.source.cdc.CdcMessage.TransactionBoundary;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원천 토픽 메시지(JSON 문자열)를 {@link CdcMessage}로 가른다(Jira 198-2). 서버 Connect는 JSON 변환기 기본값이라 메시지가 {@code
 * {"schema": …, "payload": …}}이다. 스키마 없는 메시지({@code payload}가 곧 본문)도 받는다.
 *
 * <ul>
 *   <li>{@code payload.status}가 있으면 트랜잭션 경계(BEGIN·END)
 *   <li>{@code payload.source}와 {@code payload.op}가 있으면 행 변경(c·u·d·t)
 *   <li>{@code payload}가 {@code ts_ms} 하나뿐이면 heartbeat
 * </ul>
 *
 * <p>그 밖의 모양, 스냅샷 읽기({@code op=r}, 커넥터는 {@code snapshot.mode=no_data}), 빈 메시지(삭제 뒤 tombstone, 커넥터는
 * {@code tombstones.on.delete=false})는 {@link CdcProtocolException}이다.
 */
@Component
public class CdcMessageParser {

    private final JsonMapper json;

    public CdcMessageParser(JsonMapper json) {
        this.json = json;
    }

    public CdcMessage parse(String value) {
        if (value == null || value.isBlank()) {
            throw new CdcProtocolException("빈 메시지입니다(tombstone은 받지 않는다).");
        }
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (JacksonException e) {
            throw new CdcProtocolException("JSON이 아닌 메시지입니다.", e);
        }
        JsonNode schema = root.has("payload") ? root.get("schema") : null;
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        if (payload == null || !payload.isObject()) {
            throw new CdcProtocolException("payload가 없는 메시지입니다.");
        }
        if (payload.has("status")) {
            return boundary(payload);
        }
        if (payload.has("source") && payload.has("op")) {
            return change(payload, schema);
        }
        if (payload.size() == 1 && payload.has("ts_ms")) {
            return new Heartbeat(payload.get("ts_ms").asLong());
        }
        throw new CdcProtocolException("알 수 없는 메시지 모양입니다.");
    }

    private static TransactionBoundary boundary(JsonNode payload) {
        BoundaryStatus status;
        try {
            status = BoundaryStatus.valueOf(payload.get("status").asString());
        } catch (IllegalArgumentException e) {
            throw new CdcProtocolException("알 수 없는 트랜잭션 경계입니다.", e);
        }
        String id = text(payload, "id");
        long txId = txIdOf(id);
        if (status == BoundaryStatus.BEGIN) {
            return new TransactionBoundary(status, txId, id, null, Map.of());
        }
        if (!payload.hasNonNull("event_count")) {
            throw new CdcProtocolException("END에 event_count가 없습니다(트랜잭션 %d).".formatted(txId));
        }
        Map<String, Integer> byTable = new LinkedHashMap<>();
        for (JsonNode collection : payload.path("data_collections")) {
            byTable.merge(
                    collection.path("data_collection").asString(),
                    collection.path("event_count").asInt(),
                    Integer::sum);
        }
        return new TransactionBoundary(
                status, txId, id, payload.get("event_count").asInt(), Map.copyOf(byTable));
    }

    private static ChangeEvent change(JsonNode payload, JsonNode schema) {
        Operation op =
                switch (payload.get("op").asString()) {
                    case "c" -> Operation.CREATE;
                    case "u" -> Operation.UPDATE;
                    case "d" -> Operation.DELETE;
                    case "t" -> Operation.TRUNCATE;
                    case "r" ->
                            throw new CdcProtocolException(
                                    "스냅샷 읽기(op=r)는 받지 않는다. 커넥터 snapshot.mode를 확인한다.");
                    default -> throw new CdcProtocolException("알 수 없는 변경 종류입니다.");
                };
        JsonNode source = payload.get("source");
        if (!source.hasNonNull("txId") || !source.hasNonNull("lsn")) {
            throw new CdcProtocolException("변경에 txId·lsn이 없습니다.");
        }
        long txId = source.get("txId").asLong();
        JsonNode transaction = payload.get("transaction");
        if (transaction == null || transaction.isNull()) {
            throw new CdcProtocolException(
                    "변경에 transaction 정보가 없습니다(트랜잭션 %d). 커넥터 provide.transaction.metadata를 확인한다."
                            .formatted(txId));
        }
        if (txIdOf(text(transaction, "id")) != txId) {
            throw new CdcProtocolException(
                    "변경의 transaction.id와 txId가 다릅니다(트랜잭션 %d).".formatted(txId));
        }
        return new ChangeEvent(
                op,
                text(source, "schema"),
                text(source, "table"),
                txId,
                source.get("lsn").asLong(),
                source.path("ts_ms").asLong(),
                payload.path("ts_ms").asLong(),
                nullable(payload.get("before")),
                nullable(payload.get("after")),
                schema);
    }

    // 트랜잭션 경계 id는 "txId:LSN"이다. BEGIN과 END의 LSN 부분은 다르므로 txId 부분만 쓴다.
    static long txIdOf(String id) {
        int colon = id.indexOf(':');
        try {
            return Long.parseLong(colon < 0 ? id : id.substring(0, colon));
        } catch (NumberFormatException e) {
            throw new CdcProtocolException("트랜잭션 id 형식이 아닙니다.", e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new CdcProtocolException("%s가 없습니다.".formatted(field));
        }
        return node.get(field).asString();
    }

    private static JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }
}
