# CDC 소비자 (Jira 198)

Debezium이 원천마다 토픽 하나(`{data_source.code}.all`)에 보낸 변경을 읽어 객체에 반영한다. 작은 단위로 나눠 만든다. 규칙의 근거는 결정 문서 `decisions/2026-11-05-cdc-initial-load.md` 8·9·10장(작업 폴더)이다.

| 단위 | 내용 | 상태 |
|---|---|---|
| 198-1 | Debezium 값 해석, 197과 같은 정규형 행(`SourceRow`) | 완료, 이 문서 아래 |
| 198-2 | 메시지 해석·원천 트랜잭션 조립(BEGIN~END) | [cdc-message-contract.md](cdc-message-contract.md) |
| 198-3 | txId epoch 복원 | 예정 |
| 198-4 | V12(epoch 기준, 수집 실패 기록 표) | 예정 |
| 198-5 | 원천 트랜잭션 하나 → 플랫폼 트랜잭션 하나 | 예정 |
| 198-6 | Kafka 소비 루프 | 예정 |
| 198-7 | 연결 재계산 호출(189) | 예정 |

## 198-1 값 해석

전체 읽기(197)와 CDC 소비자(198)는 같은 원천 행에서 **같은 원천 키 문자열과 같은 속성 값**을 만들어야 한다. 객체와 삭제 흔적은 `source_key`를 jsonb로 그대로 비교하기 때문이다.

```
197: JDBC 값  ── SourceTableReader ──┐
                                     ├─ SourceRow(정규형 값, 키 문자열) ── SourceRowConverter ── SyncedObject
198: Debezium 값 ── DebeziumRowDecoder ┘
```

- 입력 해석만 경로마다 다르고, 정규형 행 이후(매핑·변환·키 문자열 `SourceKeyText`)는 같은 코드다.
- `DebeziumRowDecoder`는 메시지 스키마의 형식과 카탈로그 원천 자료형(`source_column.source_data_type`)을 같이 본다. numeric은 `decimal.handling.mode=string`이라 스키마로는 문자열 컬럼과 구별되지 않기 때문이다.

| 원천 형식 | Debezium 값 | 정규형 |
|---|---|---|
| int2·int4·int8 | 정수 | Long |
| numeric | 문자열(NaN은 `NAN`) | BigDecimal(NaN·무한대는 Double) |
| float4·float8 | 실수 | Double |
| boolean | 참거짓 | Boolean |
| date | 1970-01-01부터 일수(`io.debezium.time.Date`) | LocalDate |
| time | 자정부터 µs(`MicroTime`), time(0~3)은 ms(`Time`) | LocalTime |
| timestamp | 1970-01-01T00:00부터 µs(`MicroTimestamp`), timestamp(0~3)은 ms(`Timestamp`) | LocalDateTime |
| timestamptz | ISO 문자열 UTC(`ZonedTimestamp`) | OffsetDateTime(UTC) |
| json·jsonb | 원천이 정규화한 JSON 텍스트(`io.debezium.data.Json`) | 읽은 JSON 값. 키 문자열은 텍스트 그대로 |
| uuid, text·varchar·char | 문자열 | String |

- **지원하지 않는 형식**: bytea, 배열, timetz, interval 등. `UnsupportedValueException`을 던진다(값은 메시지에 넣지 않는다). 197은 이 형식을 문자열로 읽으므로 두 경로 값이 다르다. 이 형식의 컬럼을 매핑하지 않게 막는 일은 198-5에서 정한다.
- **변경되지 않은 TOAST 값**(`__debezium_unavailable_value`)은 값에 넣지 않고 `unavailableColumns`로 알린다. 198-5는 그 속성의 기존 값을 유지한다.

### 비교 시험

`DebeziumRowDecoderParityTests`가 같은 원천 행을 두 경로로 넣어 비교한다.

- 원천 행: `src/test/resources/cdc-parity/typed.sql`(형식 17가지 + 복합 키·날짜 키·시각 키·문자 키 표, 빈 값·NaN·1970년 이전·마이크로초·시간대·이모지 포함)
- Debezium 쪽: 같은 SQL을 Debezium 3.6.3(서버와 같은 커넥터 설정, 원천 토픽 하나)에 흘려 받은 **실제 메시지** `cdc-parity/debezium-typed-events.jsonl`. SQL을 고치면 메시지도 다시 받는다.
- 확인: 모든 행의 정규형 값과 키 문자열이 같다. 모든 속성 자료형(STRING~JSON)으로 매핑했을 때 같은 객체가 나오고 변환 실패 수도 같다.
