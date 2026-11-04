# 후보 결정 이력 저장·조회 — S15P31C106-130

FN-REV-04·FN-LOG-03의 결정 이력 저장 모듈과 API 055를 구현한다. 요청 DTO·권한·후보 상태·revision·선택지·검사 검증은 129의 [결정 조건 검증](review-decision-validation.md) 모듈을 재사용한다.

## 저장 호출 계약

`ProposalDecisionHistoryService.recordValidated(proposalId, request, account)`는 이력 ID를 반환한다. Spring Bean을 통해 호출하며 호출자의 쓰기 트랜잭션이 반드시 필요하다(`MANDATORY`). 트랜잭션이 없으면 저장하지 않고 실패한다.

1. 내부 호출도 Bean Validation으로 경로 ID와 `ProposalDecisionRequest`를 검증한다.
2. 129의 `ProposalDecisionValidator`로 ADMIN 권한, 후보 행 잠금, 현재 상태·revision·선택지·승인 검사 조건을 확인한다.
3. 검증된 후보의 ID·revision과 요청의 결정·선택지·사유를 저장한다.
4. 결정자는 `LoginAccount.id()`, 시각은 주입된 `Clock`의 현재 `Instant`를 사용한다. 선택지 null은 bigint NULL로 저장하고, 사유는 임의로 자르거나 정규화하지 않는다.

`review_decision`에 결정마다 한 행을 INSERT한다. 기존 이력을 수정·삭제하지 않는다. 성공 이력 저장 뒤 호출자가 예외를 던지면 같은 트랜잭션의 INSERT도 롤백된다.

이 모듈은 후보 검토 상태·반영 상태, 온톨로지, 개별 보류 건, 검사 접수, 감사 데이터를 변경하지 않는다. 반환값은 승인 완료 응답이 아니다. 같은 후보의 이력을 여러 번 저장할 수 있으며, 최종 결정의 중복 차단은 131에서 후보 상태 전이를 같은 잠금·트랜잭션에 연결해야 완성된다.

## API 055

`GET /api/v1/proposals/{id}/decisions`

- ADMIN 세션이 필요하다. 기존 임시 비밀번호 제한을 그대로 적용한다.
- `id`: 1 이상. 없는 후보는 404 `RESOURCE_NOT_FOUND` (`details.resource=proposal_item`).
- `page`: 선택, 0 이상. `size`: 선택, 1 이상.
- 후보의 현재 revision이나 검토 상태와 무관하게 그 후보에 저장된 모든 revision의 이력을 조회한다. 다른 후보의 이력은 포함하지 않는다.
- 이력이 없거나 요청 페이지가 범위를 벗어나면 200과 빈 `data`를 반환한다. `meta.totalElements`는 해당 후보의 전체 이력 수다.
- 입력 오류 400, 미인증 401, 권한 없음 403을 공통 오류 형식으로 반환한다.

페이지 기본값 `page=0`, `size=20`은 기존 Review 조회에 맞춘 구현 기본값이다. 정렬은 `decided_at DESC, id DESC`이며 같은 결정 시각에도 순서를 고정한다. 이 기본값·정렬은 API 055 원문에 명시되어 있지 않으므로 FE 공유 및 명세 보완 대상이다. 임의의 필터나 size 상한은 추가하지 않았다.

성공 응답은 `ApiResponse`의 `data` 배열과 `meta.page/size/totalElements/hasNext`다. 각 항목은 다음 필드를 포함한다.

| 필드 | 의미 |
| --- | --- |
| `id` | 결정 이력 ID |
| `proposalItemId` | 후보 ID |
| `itemRevision` | 결정 당시 불변 revision |
| `decision` | `APPROVE`, `REJECT`, `HOLD` |
| `selectedOptionId` | 같은 후보·revision의 선택지 ID. 없으면 명시적 null |
| `reason` | 결정 사유 |
| `decidedBy` | 결정한 관리자 ID |
| `decidedAt` | UTC ISO 8601 시각 |

DB ID는 JSON 숫자다. 후보가 나중에 수정되어도 과거 revision·선택지·사유를 그대로 반환한다. 조회 때문에 후보 상태나 이력이 변경되지 않는다.

## 131 연동

API 054 `POST /api/v1/proposals/{id}/decisions`는 이번에 추가하지 않는다. 131의 상위 Service가 트랜잭션을 시작하고 이 모듈과 상태 변경·반영·감사를 함께 실행해야 한다.

- 승인: 129 검증·130 이력 저장, 실제 반영, `APPROVED`·`APPLIED` 상태 및 성공 감사가 함께 커밋되어야 한다.
- 거절·보류: 결정 이력과 해당 검토 상태 변경·감사를 함께 처리한다. 실제 반영은 하지 않는다.
- 실패: 결정 이력과 업무 변경은 롤백하고, 실패 감사는 기존 `AuditRecorder` 계약에 따라 별도 트랜잭션에 남기는 처리를 131에서 연결한다.
- 공개 POST의 오류 응답, 실제 반영 실패·감사 실패 롤백, 중복 승인·동시 결정은 131에서 통합 검증한다.

130 테스트의 성공 검사 데이터는 테스트 DB에 만든 fixture다. 실제 `CANDIDATE` 검사 실행부나 반영 서비스가 구현됐다는 의미가 아니다.

## 검증 범위와 기준

- `ProposalDecisionRepositoryTests`: 결정 3종의 메타데이터, nullable 선택지, 이력 추가·보존, 과거 revision 보존, 다른 후보·revision 선택지의 DB FK 차단.
- `ProposalDecisionHistoryTransactionTests`: 실제 검증·저장 연동, 로그인·권한·상태·revision·선택지·승인 검사 실패의 무저장, 내부 입력 검증, 외부 트랜잭션 필수, 호출자 실패 시 이력 롤백.
- `ProposalDecisionHistoryApiTests`: 명세 응답·UTC·nullable, 과거 revision, 후보 격리, 정렬·페이지, 빈 결과·404·400·401·403, 임시 비밀번호 제한, 읽기 무변경.

DB 스키마·Migration·의존성·공통 인증 구조 변경은 없다.

기준 문서:

- [API 054](https://app.notion.com/p/405bdf14d2d8813dbde3d11097ddd5df)
- [API 055](https://app.notion.com/p/405bdf14d2d8816ea256f4ccc0933ebe)
- [현재 운영 DB·ERD](https://app.notion.com/p/401bdf14d2d880e6b8ebdaeec27fa921)
- 저장소 `V2__create_operations.sql`의 `review_decision`과 기존 인덱스·복합 FK.
