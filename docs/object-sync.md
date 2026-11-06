# 원천 객체 저장 (Jira 180)

수집이 원천 행을 객체(`object_instance`)로 저장하는 내부 서비스다. 화면·외부 API는 없다. 전체 읽기(197)와 CDC 소비자(198)가 부른다.

- 코드: `ontology.service.ObjectSyncService`, SQL은 `ontology.repository.ObjectSyncRepository`
- 판단 규칙의 근거: V11 마이그레이션 주석, [개발 가이드](development-guide.md) 원천 연동 절

## 부르는 쪽이 지킬 것

- 모든 메서드는 **부르는 쪽 트랜잭션 안에서만** 돈다(`MANDATORY`). CDC 소비자는 객체 반영과 `cdc_offset` 저장을 한 트랜잭션에 넣는다.
- 매핑·변환은 부르는 쪽이 끝내고 `SyncedObject`(원천 테이블, 매핑, 객체 유형, 원천 키, 표시 이름, 속성)로 넘긴다.
- **CDC 테이블 매핑의 원천 키는 원천 PK와 같아야 한다.** 삭제 이벤트에는 PK만 오므로(REPLICA IDENTITY DEFAULT), 키가 다르면 삭제를 객체에 이을 수 없다.
- CDC 이벤트의 `txId`(32비트)는 부르는 쪽(198)이 epoch를 복원해 `SourceChange.transactionId`로 넘긴다.
- **원천 키 값의 JSON 모양을 197과 198이 똑같이 만든다.** 객체와 흔적은 `source_key`를 jsonb로 그대로 비교한다. 같은 PK `12`가 한쪽에서 숫자 `12`, 다른 쪽에서 문자열 `"12"`로 들어오면 다른 객체가 된다. 예를 들어 커넥터는 `decimal.handling.mode=string`이라 numeric PK를 문자열로 보내고, JDBC SELECT는 숫자로 읽는다. 키 변환은 한 곳에 두고 둘이 같이 쓴다.

## 메서드

| 메서드 | 부르는 곳 | 하는 일 | 쓰지 않는 경우 |
|---|---|---|---|
| `applyChange(object, change)` | 198 `c`·`u` | 객체를 만들거나 바꾼다 | 번호가 객체의 `loaded_snapshot`에서 보임(이미 적재값에 있음), `source_lsn` 이하(재전송) |
| `applyDelete(resourceId, key, change)` | 198 `d` | 그 원천 행의 객체(켜진 매핑마다)를 `DELETED_AT_SOURCE`로 바꾼다. 켜진 매핑 중 그 행의 객체가 없는 매핑이 있으면 `source_delete_mark`에 흔적도 남긴다(새로 만든 매핑이 아직 적재 전인 경우 포함) | 위와 같음 |
| `applyLoaded(object, load)` | 197 행마다 | 객체를 만들거나 바꾼다. 값이 같으면 `loaded_snapshot`·`loaded_gen`만 갱신한다 | 객체의 `source_txid`가 스냅샷에서 안 보임(객체가 더 새로움), 옛 세대, 객체가 없는데 스냅샷 뒤에 지워진 흔적이 있음 |
| `finishLoad(resourceId, mappingId, load)` | 197 매핑마다 끝에서 | 이번 읽기에 안 나온 객체를 삭제 표시하고, 스냅샷에서 보이는 삭제 흔적을 지운다 | 스냅샷 뒤에 CDC로 바뀐 객체는 두고, 옛 세대면 아무것도 안 한다 |

`FullLoad`는 전체 읽기의 `pg_current_snapshot()` 값과 수집 실행 ID다. 실행 ID가 그대로 세대(`loaded_gen`)다. `source_resource.full_load_done_gen`을 올리는 일은 197이 한다.

## 잠금

- `applyLoaded`·`finishLoad`는 `sync:resource:{id}` advisory lock을 **배타**로, `applyDelete`는 **공유**로 잡는다(트랜잭션 끝까지).
- 이유: 소비자가 "객체 없음 → 흔적 남김"을 커밋하기 전에 전체 읽기가 같은 행을 만들면, 흔적을 보지 못하고 지운 행을 되살린다. 추가·수정은 객체 유일 인덱스와 행 잠금으로 줄을 서므로 잠그지 않는다.
- 소비자끼리는 공유라 서로 막지 않는다. 전체 읽기가 쓰는 동안 그 테이블의 삭제 이벤트만 기다린다.

## 아직 하지 않은 것

- 객체가 바뀌거나 지워진 뒤의 연결 재계산(U6)은 부르는 쪽(197·198)에서 같은 트랜잭션으로 붙인다.
- 같은 원천 행이 매핑 여러 개로 객체 여러 개가 되면, 198이 매핑마다 `applyChange`를 부른다(같은 `SourceChange`).
