# CDC 소비자 (Jira 198)

Debezium이 원천마다 토픽 하나(`{data_source.code}.all`)에 보낸 변경을 읽어 객체에 반영한다. 작은 단위로 나눠 만든다. 규칙의 근거는 결정 문서 `decisions/2026-11-05-cdc-initial-load.md` 8·9·10장(작업 폴더)이다.

| 단위 | 내용 | 상태 |
|---|---|---|
| 198-1 | Debezium 값 해석, 197과 같은 정규형 행(`SourceRow`) | 완료, 이 문서 아래 |
| 198-2 | 메시지 해석·원천 트랜잭션 조립(BEGIN~END) | [cdc-message-contract.md](cdc-message-contract.md) |
| 198-3 | txId epoch 복원 | 완료(`TransactionIdRestorer`) |
| 198-4 | V12(원천별 소비 상태, 수집 실패 기록, 확인 대기) | 이 문서 아래 |
| 198-5 | 원천 트랜잭션 하나 → 플랫폼 트랜잭션 하나 | 이 문서 아래 |
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

## 198-4 V12 표

`V12__track_cdc_consumer_and_pending_checks.sql`. 표만 만들고, 쓰는 코드는 198-5 이후에 붙인다.

| 표 | 바뀐 것 | 쓰는 곳 |
|---|---|---|
| `cdc_offset` | 원천마다 한 행(`{code}.all`, 파티션 0). `last_txid`(epoch 복원 기준값), `last_commit_at`, `last_message_at`, `processing_lag_ms`, `healthy_since`, `recovery_reason`·`recovery_at` 추가 | 198-5가 원천 트랜잭션을 반영하는 같은 트랜잭션에서 쓴다. 복구가 필요하면(`recovery_reason`) `healthy_since`는 NULL(CHECK) |
| `source_load_failure` | 새 표. 객체로 만들지 못한 행(키 없음·키 형식)과 값 변환 실패. 열린 기록은 같은 실패당 하나이고 횟수만 올린다 | 197·198이 쓴다. issue로 올리지 않는다. 도착 표의 연결 기준 컬럼에 열린 기록이 있으면 미연결 판정 보류 |
| `link_check_pending` | 새 표. 원천 사이 규칙의 미연결 확인 대기(WAITING → TRANSIENT 또는 PROMOTED) | SYNC 검사(199·241). 승격과 issue 등록은 한 트랜잭션 |
| `check_run` | `sync_run_id`가 SYNC에서도 선택(`ck_check_run_sync`) | 배치가 모아서 돌리는 SYNC는 `data_basis`로 기준을 남긴다 |

시험은 `DatabaseMigrationTests`(제약마다 정상·거부).

## 198-5 원천 트랜잭션 반영

`source/cdc/CdcTransactionApplier`. 198-6 소비 루프가 원천 트랜잭션을 END까지 모은 뒤 `apply(source, tx, initialReference, receivedAt)`를 부른다. 한 호출이 한 플랫폼 트랜잭션이다(결정 문서 10.2·11장).

1. 원천의 `cdc_offset` 행을 `FOR UPDATE`로 잡는다(없으면 만든다). END가 다음 위치보다 앞이면 `ALREADY_APPLIED`(아무것도 안 씀). 복구가 필요한 원천이면 `RECOVERY_PENDING`.
2. `last_txid`(없으면 `initialReference` = 원천 xmax)로 txId epoch를 복원한다. 확신할 수 없으면 `recovery_reason = EPOCH_UNCERTAIN`, `healthy_since = NULL`, 그 원천 CDC 표 `loaded_lsn = NULL`만 쓰고 `RECOVERY_NEEDED`. 위치는 옮기지 않는다.
3. 변경이 걸린 표를 찾아 원천 테이블 행을 ID 순서로 `FOR SHARE` 잠근다. 전체 읽기 저장·매핑 변경(`FOR UPDATE`)과 잠금 순서가 같아져 교착이 없고, 반영 중 매핑이 바뀌지 않는다. 매핑 계획은 197과 같은 `FullLoadPlanner`다. 없는 표·CDC가 아닌 표·켜진 매핑이 없는 표·지금 적재할 수 없는 매핑(행 조건 등)은 건너뛴다(`skippedEvents`).
4. 변경을 원천 커밋 순서대로 쓴다. 값 해석은 **매핑한 컬럼만** 한다. 해석하지 못하는 형식의 컬럼은 빼고 다시 해석해 그 컬럼만 실패로 남긴다.

| 변경 | 처리 |
|---|---|
| `c`·`u` | 매핑마다 `SourceRowConverter` → `ObjectSyncService.applyChange`. `before`에 다른 키가 오면 옛 키부터 삭제 표시(PK 변경) |
| `u`의 TOAST 미제공 값 | 그 속성은 기존 값 유지. 객체가 없어 유지할 값이 없으면 그 속성 없이 만들고 표를 다시 읽기 대상(`loaded_lsn = NULL`)으로 |
| `d` | `before`의 키로 `applyDelete`. 그 키의 열린 수집 실패 기록을 닫는다 |
| `t` | 그 표를 다시 읽기 대상으로. 행 정리는 다시 읽기의 "안 나온 객체 삭제 표시"가 한다 |
| 데이터 불량 | `source_load_failure`에 남기고 진행(키 없음·키 형식 → 객체 없음, 값 변환·지원하지 않는 형식 → 그 속성만 뺌). 같은 매핑·키를 다시 쓸 때 이번에 실패하지 않은 열린 기록은 닫는다 |

5. 변경이 있으면 `sync_run`(CDC, SUCCEEDED) 하나와 표별 `sync_run_resource`(INCREMENTAL, LSN 구간·건수)를 남긴다. 반영한 변경이 없으면 만들지 않는다.
6. `cdc_offset`에 다음 위치(END + 1)·`last_txid`·`last_commit_at`·`last_message_at`·`processing_lag_ms`를 쓰고, `healthy_since`가 비어 있으면 지금으로 채운다.

처리 장애(DB 예외 등)는 잡지 않는다. 전체가 롤백되어 위치가 그대로이고 198-6이 같은 트랜잭션을 다시 반영한다. 결과의 `changedObjectIds`(쓰거나 삭제 표시한 객체)는 198-7 연결 재계산의 입력이다.

`markRecovery(source, reason)`는 198-6(트랜잭션 크기 초과·메시지 순서 오류·보관 초과)도 부른다. 다시 읽기 실행은 배치 감시 작업이 `RECOVERY`로 넣고, 표시를 푸는 일은 199다.

알려진 한계: 대표 속성(표시 이름)이 TOAST 미제공 값이면 표시 이름이 원천 키 값으로 바뀐다(2KB 넘는 표시 이름에서만 생긴다).

### 시험

`CdcTransactionApplierTests`(실제 PostgreSQL). 198-2의 실제 메시지(트랜잭션 756~765)를 파서·조립기에 흘려 반영한다.

- 순서대로 반영: 수정값, 삭제 표시, PK 변경(옛 키 삭제·새 키 생성), TOAST 값 유지, TRUNCATE 다시 읽기 표시, 위치·`last_txid`, 트랜잭션마다 CDC 실행
- 중간 변경에서 장애 → 객체·실행·위치 모두 없음
- 재전송: 위치로 거르고, 위치를 잃고 처음부터 받아도 LSN으로 값이 되돌아가지 않음
- 전체 읽기 스냅샷에서 보이는 트랜잭션은 건너뛰고, 안 보이는 트랜잭션은 반영
- 값 변환 실패 기록 → 키 삭제로 닫힘, 새 키는 다시 열림
- 해석할 수 없는 형식의 매핑 컬럼 하나 → 행은 반영하고 그 속성만 실패
- 객체 없이 TOAST 미제공 값 → 속성 없이 만들고 다시 읽기 표시
- epoch 불확실 → 복구 표시, 위치·객체 그대로, 다음 트랜잭션도 반영 안 함
- 매핑된 변경이 없는 트랜잭션 → 실행 없이 위치만 전진
