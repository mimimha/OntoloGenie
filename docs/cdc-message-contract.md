# CDC 원천 토픽 메시지와 원천 트랜잭션 조립 (Jira 198-2)

CDC 소비자(198)가 원천 토픽 `{data_source.code}.all`에서 받는 메시지의 약속과, 그 메시지를 원천 트랜잭션 단위로 모으는 규칙이다. 커넥터 설정과 메시지 모양은 `infra/cdc/README.md`, 반영 규칙은 결정 문서 `decisions/2026-11-05-cdc-initial-load.md` 8·10장(작업 폴더)에 있다.

- 단위 목록과 값 해석(198-1): [cdc-consumer.md](cdc-consumer.md)
- 코드: `source.cdc.CdcMessageParser`(메시지 → `CdcMessage`), `source.cdc.SourceTransactionAssembler`(메시지 → `SourceTransaction`)
- 시험 자료: `src/test/resources/cdc-tx/debezium-tx-messages.jsonl`(Debezium 3.6.3에서 받은 실제 메시지 37건, 만든 SQL은 같은 폴더 README)

## 메시지 종류

| 종류 | 알아보는 법 | 내용 |
|---|---|---|
| 변경 | `payload.source`와 `payload.op` | `c` 추가, `u` 수정, `d` 삭제, `t` TRUNCATE. `source.txId`(32비트), `source.lsn`, `source.ts_ms`(원천 커밋 시각), `ts_ms`(Debezium 처리 시각), `before`·`after`, `transaction` |
| 트랜잭션 경계 | `payload.status` | `BEGIN`·`END`, `id`(`txId:LSN`). END에 `event_count`와 `data_collections`(표별 건수) |
| heartbeat | `payload`가 `ts_ms` 하나뿐 | Connect 서버 시각. 판정 근거가 아니라 살아 있음 신호 |

서버 Connect는 JSON 변환기 기본값이라 메시지가 `{"schema": …, "payload": …}`다. 스키마 없는 메시지도 받는다.

받지 않는 것(`CdcProtocolException`): 빈 메시지(tombstone, 커넥터는 `tombstones.on.delete=false`), 스냅샷 읽기 `op=r`(커넥터는 `snapshot.mode=no_data`), `transaction` 없는 변경(`provide.transaction.metadata=true`가 꺼진 것), 알 수 없는 모양. 예외 메시지에는 원천 행 값을 넣지 않는다.

## 실제 메시지에서 확인한 것

- 한 원천 트랜잭션은 BEGIN → 변경들 → END 순서로 연속해서 온다. 표가 달라도 같은 토픽이다.
- **heartbeat가 트랜잭션 중간에 낄 수 있다**(tx 756: 첫 변경과 둘째 변경 사이).
- **PK 변경**은 `u`가 아니라 같은 트랜잭션 안의 옛 키 `d`(before에 옛 PK) + 새 키 `c`로 온다(tx 761). 별도 처리가 필요 없다.
- 변경되지 않은 TOAST 값은 `after`에 `"__debezium_unavailable_value"`(tx 763). 값 해석(198-1)이 따로 알린다.
- TRUNCATE는 `op=t`, `before`·`after` 없음(tx 765).
- 수집 대상이 아닌 표만 바꾼 트랜잭션, 아무것도 안 바꾼 트랜잭션은 **경계 메시지도 오지 않는다**.
- 트랜잭션 번호는 SAVEPOINT 등으로 건너뛸 수 있다(758 → 761).
- BEGIN과 END의 `id` 뒤쪽 LSN은 다르다. 짝은 `txId`로 맞춘다.

## 조립 규칙 (`SourceTransactionAssembler`)

원천 토픽 파티션 하나에 하나를 둔다.

| 받은 것 | 처리 |
|---|---|
| heartbeat | 넘어간다(트랜잭션 중간이어도) |
| BEGIN | 새 트랜잭션을 연다. **같은 번호의 BEGIN이 다시 오면** 커넥터가 처음부터 다시 보낸 것이라 모으던 것을 버리고 다시 모은다. 다른 번호를 모으는 중이면 오류(END 없이 다음 트랜잭션) |
| 변경 | 열린 트랜잭션에 넣는다. 열린 게 없거나 번호가 다르면 오류. `maxEvents`를 넘으면 `TooLargeTransactionException`(소비자는 그 원천을 복구 상태로 돌려 다시 읽는다. 부분 반영하지 않는다) |
| END | 번호와 **`event_count`·표별 건수**가 모은 것과 맞아야 완성(`SourceTransaction`). 아니면 오류 |
| offset이 뒤로 감 | 오류 |

- 완성된 `SourceTransaction` 하나를 플랫폼 트랜잭션 하나로 반영하고, 같은 트랜잭션에서 소비 위치를 `nextOffset()`(END 다음)으로 저장한다(198-5).
- `resumeOffset()`: 모으는 중이면 그 BEGIN, 아니면 마지막으로 받은 메시지 다음. "앞에 덜 끝난 구간이 없는 위치"다. 저장된 위치에서 다시 시작하면 늘 BEGIN이나 heartbeat부터 받는다.
- 오류(`CdcProtocolException`)가 나면 소비자는 위치를 옮기지 않고 멈춘다(처리 장애, 9.3).
