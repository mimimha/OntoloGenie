# 변경 수집·재분석·재검증 통합 검증 (Jira 241)

운영 서버(배포 f8e6708 이후, 364·366 포함, 스키마 V16)에서 원천 변경부터 문제 해결까지 한 번에 확인한 기록이다. 일시는 2026-11-12, 한국 시간.

- 원천: MES(`MES_MAIN`, data_source 2), 표 `mes.downtime`(source_resource 18, CDC, 32행)
- 규칙: `DOWNTIME_EQUIPMENT_CODE_TO_EQUIPMENT`(link_mapping 19, 설비 정지 → 설비, MANY_TO_ONE, 같은 원천이라 확인 대기 없음)
- 원천에는 시험 행 `DT-T241` 하나와 SYNC 계정 권한만 바꿨다(사용자 승인). 기존 행은 건드리지 않았고, 끝난 뒤 원천은 원래대로 32행이다.
- 플랫폼은 API(관리자 admin01)로 요청하고 DB는 읽기만 했다.

## 결과 요약

| 완료 기준 | 결과 | 근거 |
|---|---|---|
| 객체 ID 유지 | 통과 | 전체 다시 읽기 뒤에도 기존 32개 객체 ID 목록이 같음(ID·키 해시 `caa94cfe…`). 시험 행 값을 바꿔도 같은 객체 5099 |
| 원천 삭제 | 통과 | 원천 DELETE → 객체 `DELETED_AT_SOURCE`, 연결 INACTIVE. 같은 키로 다시 INSERT → 같은 객체 5099·같은 연결 9960이 ACTIVE로 돌아옴 |
| 체크포인트 | 통과 | 원천 트랜잭션마다 `cdc_offset(MES_MAIN.all)` next_offset·last_txid가 앞으로만 감(1692 → 1719, txid 1021 → 1026). 전체 다시 읽기 뒤 `loaded_lsn` 31201920 → 31257072, 복구 표시 없음 |
| 실패 재실행 | 통과 | SYNC 계정 읽기 권한을 뺀 상태의 073 → FAILED(권한 메시지), 권한 복구 뒤 073 → SUCCEEDED(32행) |
| 중복 요청 | 통과 | 같은 표 073을 겹쳐 보내면 기존 실행에 합류(`joinedExisting=true`) |
| 문제 해결 조건 | 통과 | 아래 3절. 허용되지 않는 전환 409, 통과 기록 없는 해결 422, 실패한 재검증은 확인중으로 되돌림, 통과한 재검증으로 해결 |
| 재분석 | 통과 | 018 REANALYSIS(분석 19) 10초 만에 SUCCEEDED, 기존 정의는 건너뛰어 새 제안 0건 |

## 1. 실패 재실행 (073·074·075)

| 시각 | 동작 | 결과 |
|---|---|---|
| 13:52:18 | 원천 `REVOKE SELECT ON mes.downtime FROM platform_ro` | SYNC 계정만 이 표를 못 읽음(CDC 계정·AI 계정은 그대로) |
| 13:52:32 | 073 `POST /api/v1/sources/2/sync-runs` `{"sourceResourceIds":[18]}` 두 번 | 1번째 실행 30 QUEUED, 2번째는 30에 합류 |
| 13:52:34 | 배치 처리 | 실행 30 FAILED. `errorMessage`: "SYNC 계정에 원천 테이블이나 컬럼을 읽을 권한이 없습니다. 연결 검사를 다시 실행해 권한을 확인해 주세요." 074 목록·075 상세에 같은 내용 |
| 13:52:42 | 원천 `GRANT SELECT ON mes.downtime TO platform_ro` | 권한 원래대로 |
| 13:52:5x | 073 다시 요청 | 실행 31 SUCCEEDED, FULL, 읽음·저장 32, 삭제 0. 객체 ID 목록 그대로 |

## 2. 원천 변경 → CDC 반영

| 시각 | 원천 SQL | 플랫폼(약 2~3초 뒤) |
|---|---|---|
| 13:53:08 | `INSERT … ('DT-T241', 'P101', …)` | 객체 5099 ACTIVE, 연결 9960(RULE) → 설비 P101(객체 2) ACTIVE |
| 13:53:23 | `UPDATE … equipment_code = 'ZZ999'` | 같은 객체 5099, 값만 바뀜. 연결 9960 INACTIVE |
| 13:57:08 | (주기 SYNC 413) | 문제 6 NEW, UNMATCHED, `dedupeKey` `UNMATCHED:rule:DOWNTIME_EQUIPMENT_CODE_TO_EQUIPMENT:object:5099` |
| 13:57:59 | `UPDATE … equipment_code = 'P101'` | 같은 연결 행 9960이 ACTIVE로 돌아옴 |
| 13:58:27 | `DELETE … 'DT-T241'` | 객체 5099 `DELETED_AT_SOURCE`, 연결 INACTIVE |
| 13:58:37 | 같은 값으로 다시 `INSERT` | 같은 객체 5099 ACTIVE, 같은 연결 9960 ACTIVE |
| 13:58:53 | 정리 `DELETE` | 객체 5099 `DELETED_AT_SOURCE`(논리 삭제로 남음), 원천 32행 |
| 14:02:08 | (주기 SYNC 416) | 지운 객체에 새 문제를 만들지 않음. 문제 6은 RESOLVED 그대로 |

## 3. 문제 해결 조건 (067·060·070·069)

| 순서 | 요청 | 결과 |
|---|---|---|
| 1 | 067 NEW → RESOLVED | 409 `INVALID_STATUS_TRANSITION`, `allowedStatuses=[CHECKING]` |
| 2 | 067 → CHECKING | 200 |
| 3 | 067 CHECKING → RESOLVED | 409, `allowedStatuses=[FIXED_PENDING_REVERIFY]` |
| 4 | 067 → FIXED_PENDING_REVERIFY | 200 |
| 5 | 067 → RESOLVED(`resolvedCheckRunId=413`, SYNC) | 422 `REVERIFICATION_REQUIRED` |
| 6 | 060 REVERIFY `{"kind":"ISSUES","issueIds":[6]}`, 비교 대상 413 (원천 고치기 전) | 검사 414 SUCCEEDED, 070 `outcome=FAILED`(rawValue ZZ999, "모든 비교 조건을 충족하는 도착 대상이 없습니다."). 문제는 자동으로 CHECKING |
| 7 | (원천 복구 뒤) 067 → FIXED_PENDING_REVERIFY | 200 |
| 8 | 060 REVERIFY, 비교 대상 414 | 검사 415 SUCCEEDED, 070 `outcome=PASSED`. 비교(`/checks/runs/415/comparison`)에서 객체 5099가 RESOLVED 분류 |
| 9 | 067 → RESOLVED(`resolvedCheckRunId=415`) | 200 |

069 이력: NEW(검사 413) → CHECKING → FIXED_PENDING_REVERIFY → CHECKING(검사 414, 처리자 없음) → FIXED_PENDING_REVERIFY → RESOLVED(검사 415).

## 4. 재분석 (018·019·020)

`POST /api/v1/analyses` `{"runKind":"REANALYSIS","targets":[{"sourceResourceId":18}],"basedOnSyncRunId":37}` → 분석 19 QUEUED → 10초 뒤 SUCCEEDED. `resultSummary`: LLM 호출 1회, 분석 표 `mes.downtime`, 기존 객체 유형 1개 건너뜀(`existingSkipped.objectTypes=1`), 새 제안 0건(`changeCounts` 모두 0). 020 제안 목록 0건.

## 확인하지 않은 것

- 원천 사이 규칙(확인 대기 10분)의 승격·연결로 닫기: 11-11 315 운영 연결 때 WORK_ORDER_SO_REF_TO_SALES_ORDER 5건으로 확인했다(14:45 대기 → 14:55 SYNC 368 승격).
- 수동 연결(366)로 고치는 경로: 058이 아직 문제 쪽으로 바뀌지 않아 API로 할 수 없다. 자동 시험(`CheckRunJobTests`, `LinkRecomputeServiceTests`)으로만 확인했다.
- 소비자 재시작 뒤 이어 읽기: 컨테이너를 재시작하지 않았다. 배포로 backend·batch가 재시작된(13:4x) 뒤에도 체크포인트가 이어진 것은 위 표로 보인다.
- CDC 반영 실패 반복(364 APPLY_FAILED)과 복구 경합: 운영에서 일부러 만들지 않았다. 자동 시험으로 확인했다.
