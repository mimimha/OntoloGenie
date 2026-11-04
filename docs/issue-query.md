# 정합성 문제 조회와 등록 — S15P31C106-194

## 범위와 권한

FN-ISS-01·02의 정합성 문제 목록·근거 조회 API와, 검사가 문제를 등록할 때 쓰는 중복 방지 등록 서비스를 제공한다. 두 API 모두 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 065 | `GET /api/v1/issues` | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 066 | `GET /api/v1/issues/{id}` | `data` 객체. `id`는 1 이상 |

조회는 `issue`를 읽기만 한다. 상태 변경(067), 원천 전달 기록(068), 상태 이력 조회(069)는 195·196에서 만든다.

## 목록 조건과 기본값

| 조건 | 검증 | 생략 시 |
| --- | --- | --- |
| `status` | `NEW`, `CHECKING`, `FIXED_PENDING_REVERIFY`, `RESOLVED` 중 하나 | 모든 상태 |
| `issueKind` | `UNMATCHED`, `DUPLICATE`, `VIOLATION` 중 하나 | 모든 종류 |
| `linkMappingId` | 1 이상 | 규칙 제한 없음 |
| `from`, `to` | 시간대가 붙은 ISO 8601. `from`이 `to`보다 늦으면 400 | 기간 제한 없음 |
| `page` | 0 이상 | 0 |
| `size` | 1 이상 | 20 |

- `from`·`to`는 문제 등록 시각(`createdAt`) 기준이고 양 끝을 포함한다.
- 쿼리 문자열의 `+09:00`은 `%2B09:00`으로 보내야 한다. 그대로 보내면 `+`가 공백으로 바뀌어 400이 된다.
- 정렬은 등록 시각 내림차순, 같으면 ID 내림차순이다. 페이지 기본값과 정렬은 **구현 기본값**이며 Notion 065에 적힌 계약은 아니다.

## 응답

목록과 상세는 같은 `IssueResponse`를 쓴다. nullable 필드는 `null`로 내보낸다.

- `evidence`는 저장된 근거를 그대로 주되 `causeConfirmed`는 항상 넣는다. 저장된 근거에 boolean으로 없으면 `false`다. 화면은 false일 때 "원인 미확정"을 표시한다(FN-ISS-02).
- 원인을 확인한 검사는 근거에 `"causeConfirmed": true`를 넣어 등록한다.

## 문제 등록 (`IssueRegistrationService.register`)

검사(191 수동 검사·재검증, 수집 중 분류, 후보 시험)가 문제를 찾으면 이 서비스를 부른다. API는 없다.

- 같은 `dedupeKey`의 열린 문제(`status <> RESOLVED`)가 있으면 새로 만들지 않고 그 ID를 `created=false`로 돌려준다. 기존 문제의 값과 처음 등록한 검사는 바꾸지 않는다.
- 해결된 뒤 같은 문제가 다시 나오면 새 문제로 등록한다(재발).
- 새로 만들면 `issue_history`에 `from_status=null`, `to_status=NEW`, `check_run_id`=등록한 검사, `changed_by=null`인 행을 함께 넣는다(069 계약).
- 중복은 DB의 `uq_issue_open` 부분 유일 인덱스로 막는다(`insert ... on conflict do nothing`). 동시에 같은 문제를 넣어도 하나만 남는다.
- 호출한 검사의 트랜잭션 안에서 실행한다. 검사 기록이 롤백되면 문제와 이력도 롤백된다.
- 문제는 반영된 규칙(`linkMappingId`)이나 승인 전 후보(`proposalItemId`) 중 정확히 하나에서 나와야 한다.

### `dedupeKey` 형식

`IssueDedupeKey`로 만든다. 규칙은 개정본 ID가 아니라 **규칙 코드**로 가리킨다. 규칙을 개정해도 같은 행의 같은 문제는 열린 문제 하나로 남는다.

| 경우 | 메서드 | 예 |
| --- | --- | --- |
| 반영된 규칙이 객체 하나에서 낸 문제 | `forRule(kind, linkMappingCode, objectId)` | `UNMATCHED:rule:W2E:object:501` |
| 후보 시험이 원천 행 하나에서 낸 문제 | `forProposal(kind, proposalItemId, rowKey)` | `UNMATCHED:proposal:7:row:W23` |

최대 300자다. Notion 065 예시(`UNMATCHED:link_mapping:12:object:501`)와 ERD 예시(`UNMATCHED:W2E:W23`)가 서로 달라 위 형식으로 정했다. 명세 예시를 맞춰야 한다.

## 오류와 검증

입력 오류는 400, 미인증은 401, 권한 부족은 403, 없는 상세는 404(`RESOURCE_NOT_FOUND`, `resource=issue`)다. 결과가 없으면 200과 빈 배열이다.

- `IssueApiIntegrationTests`: PostgreSQL에서 필터·기간 양 끝·페이지·정렬·`causeConfirmed` 기본값·해결/전달 필드·400/401/403/404를 확인한다.
- `IssueRegistrationServiceTests`: 등록 이력, 열린 문제 재사용(처리 중 상태 포함), 해결 뒤 재발 등록, 객체·종류별 분리를 확인한다.
- `NewIssueTests`: 키 형식과 입력 검사.

동시 등록은 테스트하지 않았다. PostgreSQL `on conflict`와 부분 유일 인덱스에 맡긴다. DB 스키마·의존성 변경은 없다.

## 기준

- Notion API 065·066. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed/065.md`, `066.md`.
- 스키마: `V2__create_operations.sql`의 `issue`, `issue_history`.
