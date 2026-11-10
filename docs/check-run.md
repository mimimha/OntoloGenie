# 검사 실행 요청·조회·실행과 규칙 입력 변환 — S15P31C106-191·190

## 범위와 권한

FN-CHK-01·02의 검사 실행 목록·상태 조회, 수동 검사·재검증 요청 접수, 접수한 검사를 돌리는 배치 실행기와, 승인 규칙을 판정 엔진 입력으로 바꾸는 변환기를 제공한다. 모든 API는 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 059 | `GET /api/v1/checks/runs` | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 060 | `POST /api/v1/checks/runs` | 새 접수·합류 202, 성공 결과 재사용 200. `data`는 검사 실행에 `joined`·`reused`를 더한 것 |
| 061 | `GET /api/v1/checks/runs/{id}` | `data` 객체(059 항목과 같은 형식) |

062 결과 조회는 정유림 담당(`CheckResultController`)이다.

**실행기(191-2):** 접수된 MANUAL·REVERIFY·SYNC 검사는 batch의 `CheckRunJob`이 돌린다(아래 "실행기"). 원천이 아니라 수집한 플랫폼 객체(`object_instance`)를 읽어 판정한다(결정 문서 `2026-11-05-cdc-initial-load.md` 9.1: 수집·연결 계산과 같은 데이터를 본다). 원천을 읽는 것은 승인 전 후보 시험(CANDIDATE)뿐이고, 이 실행기는 CANDIDATE를 가져가지 않는다.

**판정(191-3):** SYNC·MANUAL 검사는 결과를 저장한 같은 트랜잭션에서 issue를 등록하고, 원천 사이 규칙의 미연결은 확인 대기(`link_check_pending`)로 보낸다(아래 "판정"). 재검증(REVERIFY)은 issue를 만들지 않는다.

**아직 없는 것:** 주기적인 SYNC 요청(191-4. 그 전까지 SYNC 검사는 넣는 곳이 없다), 재검증의 문제별 검증 기록·이전 결과 비교(134·135), 후보 시험 실행(CANDIDATE).

**수동 검사의 역할(2026-11-06 합의):** 평소 판정은 수집 뒤 배치가 주기적으로 넣는 SYNC가 한다. 수동 검사(MANUAL)는 실패한 후보 시험을 다시 돌리거나, 장애 복구 뒤·문제가 의심될 때 고른 규칙을 다시 점검하는 데 쓴다. issue는 SYNC·MANUAL 판정에서 만들고, 원천 사이 규칙의 미연결은 확인 대기(`link_check_pending`)를 거친다(191-3).

## 060 요청

| 필드 | 값 |
| --- | --- |
| `triggerKind` | `MANUAL` 또는 `REVERIFY`. SYNC·CANDIDATE는 수집·후보가 넣으므로 이 API로 받지 않는다 |
| `scope.kind` | `ALL`, `LINK_MAPPINGS`, `ISSUES` |
| `scope.linkMappingIds` | LINK_MAPPINGS일 때만. 1~100개 |
| `scope.issueIds` | ISSUES일 때만(REVERIFY만). 1~100개 |
| `comparedToRunId` | REVERIFY일 때만 필수 |

명세 예시에는 `{"kind": "ALL"}`만 있어 나머지 범위는 구현에서 정했다.

| 범위 | 검사하는 규칙 |
| --- | --- |
| ALL | 반영 중(`link_mapping.status=ACTIVE`)인 모든 규칙 개정본 |
| LINK_MAPPINGS | 지정한 규칙 개정본. 모두 ACTIVE여야 한다 |
| ISSUES | 지정한 문제가 나온 규칙 개정본. 문제는 열려 있어야 하고(RESOLVED 아님) 반영된 규칙에서 나왔어야 하며, 그 규칙이 ACTIVE여야 한다 |

### 저장되는 범위와 데이터 기준

- `check_run.scope`에는 요청한 `kind`와 함께 **접수 시점에 정한 규칙 개정본 목록**(`linkMappingIds`, ID 오름차순·중복 제거)을 넣는다. ISSUES면 `issueIds`도 넣는다. 실행기는 이 목록만 검사한다. ALL로 요청해도 그 뒤 반영된 규칙은 들어가지 않는다.
- `check_run.data_basis`는 검사할 규칙들이 읽는 원천 자원(출발·도착 객체 유형에 활성 매핑된 자원)마다 마지막으로 성공한 수집 실행이다. 후보 검사(CANDIDATE)와 같은 형식(`CheckDataBasis`)이다.
- 응답의 `dataBasis.resources[]`는 `sourceResourceId`, `lastSyncRunId`다. V5(266) 반영본 명세의 이름을 따랐다. 저장 칸 이름은 `lastSuccessfulSyncRunId`라서 응답으로 바꿀 때 이름을 바꾼다. Notion `api_fixed` 사본의 `lastAppliedChangeSeq`는 V5 전 이름이다.

### 같은 검사 판단

`work_key` = `종류:비교대상(없으면 -):SHA-256(저장 범위 + 데이터 기준)`. 같은 키면 같은 검사다.

1. 같은 키로 성공한 검사가 있으면 그 검사를 돌려준다. HTTP 200, `reused=true`.
2. 없으면 QUEUED로 넣는다. HTTP 202, `joined=false`.
3. 같은 키로 대기·실행 중인 검사가 이미 있으면(`uq_check_run_active`) 넣지 않고 그 검사를 돌려준다. HTTP 202, `joined=true`.

- 실패한 검사는 다시 쓰지 않는다. 같은 요청이면 새로 넣는다.
- 새 수집이 성공하면 데이터 기준이 바뀌어 새 검사가 된다.
- MANUAL과 REVERIFY는 규칙과 기준이 같아도 다른 검사다.
- 동시에 같은 요청이 와도 부분 유일 인덱스와 `on conflict do nothing`으로 하나만 남는다. 그 사이 상대 검사가 끝나면 최대 3번 다시 본다.

### 거절

| 경우 | 응답 |
| --- | --- |
| 필수 누락, 허용 값 밖, 종류·범위·비교 대상의 조합이 틀림(REVERIFY인데 `comparedToRunId` 없음, MANUAL인데 있음, ALL에 ID 목록, ISSUES를 MANUAL로 등) | 400 `VALIDATION_FAILED` |
| 반영 중인 규칙이 하나도 없음(ALL) | 422 `BUSINESS_RULE_VIOLATION`, `details.field=scope` |
| 없거나 ACTIVE가 아닌 규칙 | 422, `details.linkMappingIds` |
| 없는 문제, 해결된 문제, 승인 전 후보에서 나온 문제 | 422, `details.issueIds` |
| 검사 입력으로 바꿀 수 없는 규칙(비교 조건 없음, 저장된 변환을 읽을 수 없음) | 422, `details.linkMappingId`·`reason` |
| 비교 대상이 없음, 후보 시험(CANDIDATE)임, 성공(SUCCEEDED)이 아님 | 422, `details.field=comparedToRunId` |

거절된 요청은 검사를 만들지 않는다.

**확인하지 않는 것:** REVERIFY는 "수정 자료 수집 완료 후 실행"이 명세 조건이지만, 원천을 고친 뒤 수집이 끝났는지는 서버가 알 수 없어 보지 않는다. 재검증이 고친 자료를 읽었는지는 067 해결 조건(조치완료 표시 뒤에 시작한 재검증)이 막는다. 비교 대상 검사가 같은 규칙을 검사했는지도 보지 않는다.

## 059 목록 조건

| 조건 | 검증 | 생략 시 |
| --- | --- | --- |
| `triggerKind` | `MANUAL`, `SYNC`, `REVERIFY`, `CANDIDATE` | 모든 종류 |
| `status` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` | 모든 상태 |
| `from`, `to` | 시간대가 붙은 ISO 8601. `from`이 `to`보다 늦으면 400 | 기간 제한 없음 |
| `page` | 0 이상 | 0 |
| `size` | 1 이상 | 20 |

- `from`·`to`는 요청 접수 시각(`requestedAt`) 기준이고 양 끝을 포함한다. `+09:00`은 `%2B09:00`으로 보낸다.
- 정렬은 접수 시각 내림차순, 같으면 ID 내림차순이다. 페이지 기본값과 같은 시각의 정렬은 구현 기본값이다.
- 061은 없는 ID면 404(`resource=check_run`)다.

## 실행기 (191-2, `CheckRunJob`)

batch 프로필의 작업 표 처리기(`QueuedJobHandler`, `CHECK_RUN`)다. `trigger_kind <> CANDIDATE`인 QUEUED 행만 가져간다(작업 표 처리기에 `claimCondition`을 더했다. 기본은 모든 행).

**run**: 읽기 전용 REPEATABLE READ 트랜잭션 하나에서 규칙·객체를 한 스냅샷으로 읽는다. 쓰지 않으므로 끝나면 되돌린다.

1. 접수 때 저장한 `scope.linkMappingIds`와 `data_basis`를 읽는다.
2. 규칙마다 190 변환기로 엔진 입력을 만든다.
3. 출발·도착 유형의 객체를 읽는다. 대상은 `status = ACTIVE`이고 활성 매핑(`object_source_mapping.is_active`)으로 규칙의 원천 자원에서 수집한 객체다. 원천에서 지운 객체, 플랫폼에서 만든 객체(원천 키 없음), 비활성 매핑에서 온 객체는 빠진다.
4. 행 조건(`source_filter`·`target_filter`)을 객체 속성에 적용한다(`ObjectRowFilter`). 연결 재계산(181)과 같은 두 형식(`{}`, `{"property", "equals"}`)만 읽는다.
5. 속성을 `유형코드.속성코드` 이름으로 `CheckRow`에 넣고(원천 자원 = 매핑의 원천 자원, 원천 키 = `source_key`, 객체 ID), 판정 엔진으로 판정한다.

**규칙 건너뛰기:** 아래 경우에는 그 규칙만 판정하지 않고 감사 결과(`skippedRules[]`: `linkMappingId`, `reason`)에 남긴다. 모든 규칙을 건너뛰면 검사는 FAILED("검사할 수 있는 규칙이 없습니다. …")이다. DB 오류는 검사 전체 실패다.

- 규칙이 더 이상 ACTIVE가 아님(접수 뒤 폐기·교체)
- 비교 조건·변환·행 조건을 읽을 수 없음
- 데이터 기준에 규칙이 읽는 자원이 빠짐(접수 뒤 매핑이 추가됨)
- 도착 값 변환 실패(엔진이 거절)

**complete**: 검사 행을 잠가(`for update`) RUNNING인지 본 뒤, 판정한 규칙마다 `check_result` 한 행을 JPA로 저장하고(`CheckResult.of`) SUCCEEDED로 바꾼다.

| 칸 | 값 |
| --- | --- |
| 대상·건수 | 판정 결과에서 그대로(`ck_check_result_target`·`ck_check_result_counts`) |
| `previous_result_id` | 같은 규칙 개정본의 가장 최근 결과. 결과는 성공하는 트랜잭션에서만 저장되므로 성공한 검사의 결과다 |
| `sample_rows` | 정상이 아닌 행 최대 20개, 객체 ID 순. `objectInstanceId`, `sourceResourceId`, `sourceKey`, `classification`, `rawValue`, `transformedValue`, `reason`, `targetObjectIds`. 조건이 여럿이면 값을 조건 순서대로 ` / `로 잇는다 |

**감사 결과 줄:** `triggerKind`, `checkedRuleCount`, `skippedRules[]`. 원천 값은 넣지 않는다.

**알려 둘 것**

- 건너뛴 규칙이 있어도 성공한 검사는 같은 `work_key` 요청에 다시 쓰인다(060 성공 재사용). 데이터 기준이 바뀌면 새 검사가 된다.
- 비용: 규칙마다 양 끝 유형의 활성 객체를 모두 읽는다(181과 같은 MVP 한계, Jira 335와 같이 본다).

## 판정 (191-3, `CheckIssueJudge`)

SYNC·MANUAL 검사에만 한다. 결정 문서 `2026-11-05-cdc-initial-load.md` 8장과 191 설계안 4장, 2026-11-10 결정(원천 사이 중복·위반은 바로 issue, 건너뛴 규칙은 감사 결과에만, FULL_COMPARE 표는 마지막 성공 수집 기준)을 따른다.

**규칙 분류**(run 스냅샷): 출발·도착 유형에 활성 매핑된 원천 테이블이 모두 같은 원천이고 모두 CDC면 같은 원천, 그 밖은 원천 사이다.

| 분류 | 미연결 | 중복·위반 |
| --- | --- | --- |
| 같은 원천 | 바로 issue | 바로 issue |
| 원천 사이 | 확인 대기 | 바로 issue |

**건너뛰기**(규칙 단위, run 스냅샷): 아래면 판정하지 않고 결과도 남기지 않는다. 이유는 감사 결과 `skippedRules`에만 남는다.

- CDC 표가 전체 적재 전(`loaded_lsn IS NULL`)
- CDC 표의 전체 읽기(FULL)가 대기·실행 중
- CDC 표의 원천에 복구가 필요(`cdc_offset.recovery_reason`)
- FULL_COMPARE 표가 한 번도 수집되지 않음

**미연결 보류**: 도착 테이블에 도착 유형의 열린 수집 실패(`source_load_failure`)가 있으면 그 규칙의 미연결은 issue·확인 대기로 보내지 않는다. 키 실패(객체를 만들지 못한 행)이거나 비교 조건의 도착 속성 값 실패인 경우다(설계 검토 R5). 어느 출발 행이 그 행과 맞았을지 알 수 없어 규칙 단위로 보류한다. 그동안 그 규칙의 확인 대기도 건드리지 않는다.

**issue 등록**: 194 `IssueRegistrationService`를 쓴다.

- `dedupeKey = 종류:rule:규칙코드:object:출발객체ID`. 같은 열린 문제가 있으면 새로 만들지 않는다. 규칙을 개정해도 같은 문제로 묶인다.
- 원본값·변환값은 표본과 같은 모양이다.
- `evidence`: `ruleCode`, `revision`, `reason`, `transformedValues`, `targetObjectIds`, `crossSource`, `causeConfirmed=false`. 승격이면 `pendingId`, `pendingFirstSeenAt`이 더 붙는다.

**확인 대기**(원천 사이 규칙의 미연결, 검사마다)

1. 이번에 미연결인 출발 객체: 대기가 없으면 WAITING으로 넣고, 있으면 `last_check_run_id`만 바꾼다.
2. 이 규칙의 WAITING 중 이번에 미연결이 아닌 것은 일시 불일치(TRANSIENT)로 닫는다. 출발 객체를 원천에서 지웠으면 SOURCE_DELETED, 아니면 LINKED다(중복·위반으로 바뀐 경우도 LINKED).
3. 아직 미연결인 WAITING은 아래를 모두 만족하면 issue(UNMATCHED)로 올리고 PROMOTED로 닫는다. 같은 트랜잭션이다.
   - 규칙의 CDC 원천이 모두 지금 정상(`healthy_since`가 있고 복구 필요 없음)이다.
   - 규칙의 FULL_COMPARE 표마다 처음 본 시각 뒤에 끝난 성공 수집이 있다.
   - `max(처음 본 시각, CDC 원천들의 healthy_since)`부터 대기 시간이 지났다. 대기 시간은 `axcore.check.pending-wait`이고 기본 10분이며 246에서 확정한다.
4. 반영 중이 아닌 규칙 개정본의 WAITING은 RULE_RETIRED로 닫는다(모든 규칙 대상).

FULL_COMPARE 표의 기준을 "마지막 성공 수집 시각부터 대기"로 하면, 수집 주기가 대기 시간보다 짧을 때 시작 시각이 계속 밀려 승격되지 않는다. 그래서 "처음 본 뒤 성공 수집이 한 번 더 있었고 대기 시간이 지남"으로 구현했다.

**감사 결과 줄**: `judgment` 아래에 `issuesCreated`, `issuesExisting`, `heldUnmatched`, `pendingAdded`, `pendingClosed`, `pendingPromoted`를 남긴다.

**한계**

- 원천 사이에서 같은 원천으로 분류가 바뀐 규칙의 WAITING은 닫지 않는다(닫을 이유 코드가 없다).
- 보류는 규칙 단위라, 수집 실패가 열려 있는 동안 그 규칙의 미연결은 하나도 issue가 되지 않는다.

## 규칙 → 판정 엔진 입력 (190, `LinkMappingRuleLoader`)

실행기(191-2)와 060 접수가 함께 쓴다. API는 없다.

- `load(linkMappingId, 실행 데이터 기준)` → `LinkMappingRule`
  - `input`(`CheckRuleInput`): 대상은 규칙 개정본 ID, 관계 수는 `link_type.cardinality`, 비교 조건은 `link_mapping_condition`의 순서대로.
  - 조건의 속성 이름은 `객체유형코드.속성코드`(예: `WORKER_x.id`)다. 판정 엔진은 원천 행 값(`CheckRow.values`)에서 이 이름으로 값을 찾으므로, 실행기는 행을 읽을 때 같은 이름으로 값을 넣어야 한다.
  - 변환은 저장 형식 `[{"op": 함수, "arg": 인자}]`(037~039와 같음)을 `TransformStep`으로 바꾼다.
  - 데이터 기준은 실행 전체 기준에서 이 규칙이 읽는 자원만 남긴다.
  - `sourceType`·`targetType`: 관계의 출발·도착 객체 유형(ID·코드). 실행기가 이 유형의 객체를 읽는다.
  - `sourceResourceIds`·`targetResourceIds`: 출발·도착 유형에 활성(`is_active`) 매핑된 원천 자원.
  - `sourceFilter`·`targetFilter`: `link_mapping`의 행 조건. 판정 엔진은 행 조건을 모르므로 행을 읽는 쪽이 적용한다.
- 오류
  - `IllegalArgumentException`: 규칙이 없음, 비교 조건이 없음, 실행 기준에 규칙이 읽는 자원이 빠짐.
  - `IllegalStateException`: 저장된 변환·행 조건을 읽을 수 없음.

## 검증

- `CheckRunApiIntegrationTests` 32개: 접수(ALL·LINK_MAPPINGS·ISSUES), 데이터 기준(마지막 성공 수집, 수집 전 null), 합류(QUEUED·RUNNING), 성공 재사용, 새 수집·실패 뒤 새 검사, MANUAL·REVERIFY 분리, 422 각 경우, 400 12가지, 목록 필터·정렬·페이지, 상태 조회·저장 기준 이름 변환·404, 401·403(역할·CSRF).
- `LinkMappingRuleLoaderTests` 7개: 변환 결과(객체 유형, 조건 이름·변환·관계 수·자원·행 조건·기준 걸러내기), 바꾼 입력으로 판정 엔진이 실제로 판정함, 비활성 매핑 제외, 기준 누락·조건 없음·없는 규칙·읽을 수 없는 변환 거절.
- `CheckRunJobTests` 7개: 작업 표에서 가져가 규칙별 결과 저장(건수·표본·원천 키), 지운·플랫폼·비활성 매핑 객체 제외, 행 조건 적용, 같은 규칙 직전 결과 연결, 판정할 수 없는 규칙 건너뛰기와 감사 결과, 모두 건너뛰면 FAILED, 후보 시험은 가져가지 않음.
- `CheckIssueJudgmentTests` 9개: 같은 원천은 모든 문제를 issue로 한 번만, 원천 사이 미연결은 확인 대기·중복은 issue, 연결·출발 삭제로 닫기, 대기 시간 뒤 승격, FULL_COMPARE 수집이 처음 본 뒤 없거나 CDC 원천이 비정상이면 승격하지 않음, 도착 키 실패로 미연결 보류, 적재 전·복구 중 건너뛰기, 폐기한 규칙의 대기 닫기, 재검증은 issue 없음.
- `CheckResultCreateTests` 3개: 판정 결과로 만든 검사 결과를 JPA로 저장(규칙·후보 대상, 건수, null 값 표본, 직전 결과), 잘못된 ID 거절.

DB 스키마·의존성 변경은 없다(확인 대기 표는 V12에 있다. Entity `LinkCheckPending`을 새로 둔다). 공용 코드 변경: 작업 표 처리기 `claimCondition`(기본은 모든 행이라 기존 처리기는 그대로), 정유림님 `CheckResult` 엔티티에 생성 메서드 `of`, `CheckResultRepository`에 직전 결과 조회.

## 기준

- Notion API 059·060·061. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed_266/059.md`~`061.md`(V5 반영본).
- 스키마: `V2__create_operations.sql`의 `check_run`, `V1__create_ontology_core.sql`의 `link_mapping`·`link_mapping_condition`.
