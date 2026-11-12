# 정의 변경 이력 (S15P31C106-187, API 044)

정의(객체 유형·속성·관계 유형·객체 매핑·속성 매핑·연결 규칙)를 누가 언제 무엇에서 무엇으로 바꿨는지 보여 준다(FN-ONT-07·FN-LOG-03). 정의가 바뀌는 길은 둘이다.

| 바뀌는 길 | 감사 기록의 Action 코드 | 남기는 곳 |
|---|---|---|
| 관리자 직접 편집(정의 API) | 동작별 관리 Action 14개(아래 표, V18) | 각 정의 API → `DefinitionEditRecorder` |
| 후보 승인 반영 | `DECIDE_PROPOSAL` | 승인 흐름(131)의 반영기(`docs/proposal-apply.md`) |

044는 두 길의 변경을 함께 보여 주고, `actionCode`로 구분한다.

## 직접 편집 기록 (V18)

187 전에는 정의 API가 감사 기록을 남기지 않았다. V18이 권한 코드 `ONTOLOGY_MANAGE`(관리자)와 관리 Action(`RECORD`, 091로 실행하지 않음) 14개를 넣는다. 데이터만 넣고 표 구조는 바꾸지 않는다.

| Action 코드 | API | 바뀌는 표 | 변경 |
|---|---|---|---|
| `CREATE_OBJECT_TYPE`·`UPDATE_OBJECT_TYPE`·`DELETE_OBJECT_TYPE` | 022·024·025 | `object_type` | INSERT·UPDATE·DELETE |
| `CREATE_PROPERTY`·`UPDATE_PROPERTY`·`DELETE_PROPERTY` | 027·028·029 | `property_definition` | INSERT·UPDATE·DELETE |
| `CREATE_LINK_TYPE`·`UPDATE_LINK_TYPE`·`DELETE_LINK_TYPE` | 031·032·033 | `link_type` | INSERT·UPDATE·DELETE |
| `CREATE_OBJECT_MAPPING`·`UPDATE_OBJECT_MAPPING` | 035·036 | `object_source_mapping` | INSERT·UPDATE |
| `CREATE_PROPERTY_MAPPING`·`UPDATE_PROPERTY_MAPPING`·`DELETE_PROPERTY_MAPPING` | 038·039·101 | `property_source_mapping` | INSERT·UPDATE·DELETE |

- **기록 위치:** 정의 서비스(`ObjectTypeService` 등)는 후보 승인 반영기도 그대로 쓴다. 그래서 기록은 서비스가 아니라 직접 편집 API(컨트롤러)가 `DefinitionEditRecorder`를 거쳐 남긴다. 승인으로 바뀐 정의가 두 번 남지 않는다.
- **성공:** 편집과 같은 트랜잭션에서 `action_execution`(SUCCEEDED, 대상 = 바뀐 표·행 ID)과 바뀐 행 하나의 `entity_change`를 남긴다. 전후는 DB 컬럼 그대로(snake_case, `to_jsonb`)다. 수정했지만 값이 같으면 `entity_change`는 남기지 않는다(`result.changedRows = 0`).
- **업무 거절:** 편집은 롤백하고 BLOCKED 기록을 따로 커밋한다(`result.errorCode`·`details`, 차단 이유). 계정 관리(005·007)와 같은 방식이다. 관리자가 아니라 거절(403)되거나 요청 형식 오류(400)는 기록 전에 끝난다.
- **입력:** 경로의 대상 ID(`id`, 속성 등록은 `objectTypeId`)와 요청 본문 칸. 정의 요청에는 비밀값이 없다.
- **변경 전의 정확성:** 수정의 변경 전은 서비스가 행을 잠그기 전에 읽는다. 수정 요청은 `rowVersion`을 함께 보내 그 사이 다른 수정이 있으면 409로 거절되므로, 성공한 수정의 변경 전은 정확하다. 삭제는 참조가 있으면 막혀 연쇄로 지워지는 행이 없다.
- 매핑 변경이 일으키는 다시 읽기 표시(`source_resource.loaded_lsn = NULL`)는 정의가 아니라 수집 상태라 `entity_change`에 넣지 않는다.

## 044 조회

`GET /api/v1/ontology/changes` · ADMIN · 최근 실행부터(`requestedAt` 내림차순, 같은 시각이면 변경 ID 내림차순).

| 조건 | 설명 |
|---|---|
| `tableName` | `object_type`, `property_definition`, `link_type`, `object_source_mapping`, `property_source_mapping`, `link_mapping` 중 하나. 그 밖이면 400 |
| `rowId` | 정의 테이블의 PK(`row_key.id`). `tableName`과 함께. 없으면 400 |
| `from`·`to` | 변경을 남긴 실행의 `requestedAt`. from 포함·to 미포함, from < to |
| `page`·`size` | 0부터, 기본 20, 최대 100 |

응답 한 줄: `id`(entity_change), `actionExecutionId`, `tableName`, `rowKey`, `changeKind`, `beforeData`, `afterData`, `actorId`, `actorType`, `requestedAt`, `actionCode`. 정의가 아닌 표(`proposal_item`, `object_link` 등)의 변경은 나오지 않는다.

조회는 JdbcClient다(`DefinitionChangeQueryRepository`). 감사 두 표와 Action 정의를 잇고 jsonb 행 키로 거르는 조회라 감사 이력 조회(096)와 같은 기준이다(조회 쿼리 기준 초안 2-2). 변경 전후 읽기(`DefinitionSnapshotRepository`)도 JdbcClient로, 정의 행을 DB 컬럼 그대로 남기려고 `to_jsonb`로 읽는다.

## 알려진 한계

- 187 전에 직접 편집한 정의는 기록이 없다.
- 후보 승인 반영기가 남기는 변경 후 내용은 반영기마다 고른 칸만 담는다(직접 편집처럼 행 전체가 아니다).
- 연결 규칙(`link_mapping`)은 직접 편집 API가 없어 승인 반영의 변경만 나온다.

## 시험

`DefinitionChangeApiTests` 4개(실제 보안 설정·DB):

- 직접 편집 16번(14개 동작 모두)이 순서대로 SUCCEEDED·권한 `ONTOLOGY_MANAGE`·대상 표와 같은 `entity_change` 하나(INSERT·UPDATE·DELETE에 맞는 전후)로 남고, 044가 한 행의 수정·등록과 삭제·등록을 전후와 함께 돌려준다.
- 값이 같은 수정은 바뀐 행 없이 남고, 오래된 rowVersion(409)과 쓰이는 유형 삭제(409 IN_USE)는 롤백 뒤 BLOCKED로 남는다.
- 후보 승인(`DECIDE_PROPOSAL`)의 정의 변경은 함께 나오고 `proposal_item` 변경은 빠지며, 페이지·기간 조건이 맞다.
- 조건 검증(400), VIEWER 403, 비로그인 401, VIEWER의 정의 편집은 기록 없이 403.
