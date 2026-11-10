# 확정 연결 저장 (Jira 329, FN-ONT-05)

관리자가 확정한 연결을 `object_link`에 저장하는 내부 서비스(`ConfirmedLinkService`)다. 화면·외부 API는 없다. 규칙 연결(origin=RULE)은 연결 재계산(`docs/link-recompute.md`)이 맡고, 여기서는 나머지 두 출처를 맡는다.

| 메서드 | origin | 부르는 곳 |
|---|---|---|
| `saveManual(ConfirmedLinkRequest)` | MANUAL | 보류 건 승인(238, API 058 APPROVE) |
| `saveDocument(ConfirmedLinkRequest)` | DOCUMENT | DOCUMENT_LINK 후보 반영기(`docs/proposal-apply.md`) |

- 부르는 쪽 트랜잭션 안에서만 돈다(`MANDATORY`). 보류 건 상태 변경과 감사 기록은 부르는 쪽이 같은 트랜잭션에서 한다.
- 요청: 관계 유형·출발·도착 객체 ID, 승인자(`confirmed_by`), 근거 후보(`proposal_item_id`, 없으면 null), 근거(`evidence`).
- 결과 `SavedLink`: 연결 ID, `outcome`(CREATED·REACTIVATED·ALREADY_LINKED), 되살린 행의 이전 출처, 감사 `changes`에 넣을 전후 내용(`change`, ALREADY_LINKED면 null).

## 순서

1. 같은 객체끼리면 거절한다.
2. 양 끝 객체를 ID 순서로 **공유 잠금**(`for share`)하며 읽고 존재·유형·활성을 본다.
3. 규칙 재계산과 **같은 관계 유형 잠금**(`pg_advisory_xact_lock(hashtext('link:type:{id}'))`, `RuleLinkRepository.lockLinkType`)을 잡는다.
4. 쌍(관계 유형·출발·도착)의 기존 행을 본다.
5. 관계 수 제약을 검사한다.
6. 새로 넣거나 비활성 행을 되살린다.

잠금 순서(객체 → 관계 유형)는 수집과 맞춘 것이다. 수집(197·198)은 객체를 고친 뒤 마지막에 관계 유형을 잠근다. 반대로 잡으면 "수집은 객체를 잡고 관계 유형을 기다리고, 저장은 관계 유형을 잡고 객체를 기다리는" 교착이 난다. 객체 공유 잠금 덕분에 수집이 그 객체를 원천 삭제로 바꾸는 중이면 그 커밋을 기다린 뒤 바뀐 상태를 보고 거절한다.

## 쌍의 기존 행

`uq_object_link` 때문에 쌍마다 행이 하나다.

| 기존 행 | MANUAL 저장 | DOCUMENT 저장 |
|---|---|---|
| 없음 | 새로 넣음(CREATED) | 새로 넣음(CREATED) |
| 비활성(RULE·MANUAL·DOCUMENT) | 그 행을 MANUAL로 되살림(REACTIVATED). 규칙 연결이었으면 근거 규칙을 뗀다 | 그 행을 DOCUMENT로 되살림 |
| 활성 DOCUMENT | `LINK_ALREADY_EXISTS`(409) | 그대로 두고 성공(ALREADY_LINKED) |
| 활성 RULE·MANUAL | `LINK_ALREADY_EXISTS`(409) | `LINK_ALREADY_EXISTS`(409) |

- 비활성 규칙 연결을 확정 연결로 바꾸는 것은 관리자 확정이 우선이기 때문이다(2026-11-10 결정). 바꾼 뒤로는 규칙 재계산이 이 행을 건드리지 않는다(결정 U6).
- 같은 메모가 같은 설비를 두 번 언급한 문서 연결은 실패로 보지 않는다(2026-11-10 결정). 근거는 첫 언급만 남는다.
- 409의 `details`에는 기존 연결 ID(`linkId`)와 출처(`origin`)가 있다. 238은 이걸로 "이미 연결됨"을 안내할 수 있다.

## 검증과 오류

| 경우 | 코드 | 상태 |
|---|---|---|
| 없는 관계 유형·객체 | `RESOURCE_NOT_FOUND`(`resource`=link_type·object_instance) | 404 |
| 같은 객체끼리 | `LINK_SELF_REFERENCE` | 422 |
| 객체 유형이 관계 유형의 출발·도착 유형과 다름 | `LINK_END_TYPE_MISMATCH`(`end`, 기대·실제 유형 ID) | 422 |
| 원천에서 삭제된 객체 | `LINK_END_NOT_ACTIVE`(`end`) | 422 |
| 관계 수 초과 | `LINK_CARDINALITY_EXCEEDED`(`cardinality`, `end`) | 422 |
| 문서 근거의 `expression_kind`가 OBSERVED·ESTIMATED가 아님 | `LINK_EVIDENCE_INVALID` | 400 |
| 같은 쌍의 활성 연결 | `LINK_ALREADY_EXISTS`(`linkId`, `origin`) | 409 |

관계 수 제약은 **모든 출처의 활성 연결**로 센다. 반대쪽 끝이 원천에서 삭제된 연결은 세지 않는다.

- 도착이 ONE(ONE_TO_ONE·MANY_TO_ONE): 출발에서 이미 나가는 활성 연결이 있으면 거절
- 출발이 ONE(ONE_TO_ONE·ONE_TO_MANY): 도착으로 이미 들어오는 활성 연결이 있으면 거절

## 동시 실행

규칙 재계산(181)과 같은 관계 유형 잠금을 잡으므로, 같은 쌍을 동시에 넣으려 해도 한쪽이 기다렸다가 먼저 커밋된 행을 보고 판단한다. `uq_object_link` 위반으로 끝나지 않는다(`ConfirmedLinkServiceTests`의 동시 실행 테스트).

## 저장 방식

`object_instance`·`object_link`는 아직 Entity가 없는 테이블이라 JdbcClient(`ConfirmedLinkRepository`)로 다룬다. 규칙 연결(`RuleLinkRepository`)과 같은 방식이다(`.ai/rules/database.md`). 관계 유형은 JPA Repository로 읽는다. `object_link` Entity를 둘지는 담당 도메인과 정한다.

## 한계

- 규칙 재계산은 관계 수를 규칙 연결끼리만 센다. 그래서 MANUAL 연결이 있는 출발에 나중에 규칙 연결이 더 붙으면 관계 수를 넘을 수 있다(`docs/link-recompute.md`).
- 확정 연결의 비활성화(예: 끝 객체가 원천에서 삭제됨)는 아직 없다. 조회 쪽이 끝 객체 상태를 함께 봐야 한다.
- 객체 공유 잠금과 수집의 객체 수정이 서로 다른 순서로 두 객체를 잡으면 PostgreSQL이 교착을 감지해 한쪽을 실패시킬 수 있다. 저장 쪽이 실패하면 다시 시도하면 된다.
