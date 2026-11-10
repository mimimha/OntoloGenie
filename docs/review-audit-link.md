# 후보 승인 검사 근거 연결 (S15P31C106-139, 1~3단계)

## 목표와 범위

후보 ID·revision → 승인 조건에 사용한 검사 실행 → 결정 ID → 감사 실행을 연결한다. 126의 revision·검사 접수, 130의 결정자·시각·사유, 131의 결정·반영·감사 트랜잭션, 212의 공통 저장, 214의 기존 조회를 재사용한다. 실제 종류별 반영기와의 통합 검증은 이번 범위에 포함하지 않는다.

## 구현 계획과 전달 계약

1. 기존 식별자를 대조한다. `proposal_revision`과 `review_decision`의 후보·revision, `check_run.scope`와 `check_result`의 후보·revision, 감사의 `input`·`result`를 연결한다.
2. `ProposalDecisionCheckRepository.findSuccessfulCheckRunId`는 `Optional<Long>`을 반환한다. Validator는 잠긴 후보와 근거를 `ValidatedDecision(ProposalItem item, Long basisCheckRunId)`로, HistoryService는 저장한 결정과 근거를 `RecordedDecision(long decisionId, Long basisCheckRunId)`로 전달한다. 검증·검사 선택은 한 번만 수행한다.
3. DecisionService는 전달받은 검사 ID를 기존 `action_execution.result.basisCheckRunId`에 넣는다. 실패 처리에 필요한 ID도 요청별 `Attempt`에 보관한다. `AuditRecorder`가 성공과 변경 내역을 업무 트랜잭션에서, 차단·실패를 롤백 후 별도 트랜잭션에서 저장한다.

새 테이블·컬럼·의존성은 추가하지 않는다. 054 요청·응답 필드는 그대로이며, 기존 096·097·098의 자유 형식 `result`에 필드가 추가된다. API 원본 명세에는 이 필드의 의미와 예시를 반영해야 한다. 원격 명세를 이번 작업에서 수정하지 않는다. ERD 구조 변경은 없다.

## 검사 선택

- 기존 허용 조건을 유지한다: `CANDIDATE`, `SUCCEEDED`, 동일 후보·revision의 결과 존재, 검사 범위에도 같은 후보·revision 포함.
- 조건에 맞는 성공 검사 중 `requested_at DESC, id DESC` 첫 실행을 서버의 승인 근거로 선택한다. 이후 시점에 최신 검사를 다시 조회하지 않는다. 이 값은 관리자가 화면에서 실제로 열어 본 검사를 증명하는 값이 아니다.
- 더 최근의 검사 실패가 있어도 기존 성공 검사를 허용하던 조건을 유지한다. `SUCCEEDED`는 검사 실행 완료이며 미연결 건수 0을 뜻하지 않는다.
- 거절·보류와, 후보 검사 대상이 아닌 종류(LINK_RULE 외, `CandidateCheckTarget`)의 승인은 검사를 요구하지 않고 `basisCheckRunId=null`을 저장한다. 승인 검증에서 근거를 전달받기 전 차단·오류도 `null`이다.

## 저장과 조회

성공 감사에는 기존 `input.proposalItemId`, `input.revision`, `result.decisionId`, `result.revision`과 함께 `result.basisCheckRunId`가 남는다. 반영 대상과 변경 전후 기록도 같은 감사에 기존 방식으로 저장한다. 097·098의 ADMIN 조회와 비밀값 제거는 기존 공통 기능을 따른다.

반영·감사 저장·커밋 실패 시 업무 변경과 성공 감사는 롤백된다. 이미 전달받은 검사 ID만 실패 감사에 보존하고, 롤백된 결정 ID나 변경 내역은 남기지 않는다. 기존 감사에는 새 필드가 없을 수 있으며, 과거 근거를 추측해 소급 채우지 않는다.

## 검증

- 기존 결정 관련 테스트로 권한·잠금·revision·선택지·오류 계약을 확인한다.
- 실제 PostgreSQL에서 다른 후보·revision·검사 종류·미완료 실행·결과 없는 실행을 제외하고, 여러 성공 검사의 선택 순서를 확인한다.
- 정상 승인 감사의 검사 ID, 거절·보류의 null, 반영·감사·커밋 실패의 근거 보존과 결정 ID 부재를 확인한다.
- 차단 감사의 `basisCheckRunId`가 필드 누락이 아닌 JSON null인지 확인한다(`ProposalDecisionApiTests.assertBlockedAudit`).
- 반영 중 또는 승인 이후 다른 검사가 생겨도 이미 고른 검사 ID가 유지되고 기존 감사 상세 API에서 조회되는지 확인한다.

## 남은 작업

- 종류별 반영기(189)가 구현되면 실제 반영기로 승인·감사 연결을 통합 검증한다. 지금은 모든 종류가 `APPLY_KIND_NOT_SUPPORTED`이고 테스트는 대체 반영기를 쓴다.
- API 원본 명세(096·097·098)에 `result.basisCheckRunId`의 의미와 예시를 반영한다.
