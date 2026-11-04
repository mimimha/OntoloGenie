# 후보 결정 이력 저장·조회 구현 계획 — S15P31C106-130

> **For agentic workers:** 구현 요청 후 `superpowers:executing-plans`로 이 계획을 순서대로 실행한다. 2026-11-04 사용자가 이 계획의 구현과 129 코드 포함을 요청했다. Commit·Push·원격 변경은 수행하지 않는다.

**Goal:** 129의 결정 조건 검증을 재사용해 결정자·시각·대상 revision·결정·선택지·사유를 저장하고 후보별 결정 이력을 조회한다.

**Architecture:** 기존 `review_decision`에 JDBC로 이력을 추가·조회한다. 저장 Service는 호출자 트랜잭션 안에서 129 검증과 행 잠금을 재사용한다. 131이 상태 변경·실제 반영·감사를 같은 트랜잭션으로 연결하고 API 054를 공개한다. API 055의 HTTP 계약은 원문 확인 후 확정한다.

**Tech Stack:** 기존 Java 21, Spring Boot 4.1.2, Spring MVC/Validation/Security, JPA, JdbcClient, PostgreSQL, JUnit/Mockito/MockMvc/Testcontainers, Spotless.

**Spec:** [Jira 130](https://ssafy.atlassian.net/browse/S15P31C106-130), FN-REV-04·FN-LOG-03, 관련 API 054·055. 129의 `cc81b37` 및 그 커밋의 `docs/review-decision-validation.md`, `V2__create_operations.sql`의 `review_decision`을 확인했다. 구현 시 브라우저로 API 054·055와 현재 운영 DB 페이지를 확인했다. API 055의 `itemRevision`을 포함한 8개 응답 필드·ADMIN 권한·페이지 입력 조건을 그대로 구현한다. 아래 초기 확인 과제의 해결 내용은 실행 기록을 따른다.

## 확인한 상태와 설계 경계

- 계획 작성 시 130 브랜치는 `feature/130-review-decision-history`, HEAD는 `0582880`, 작업 트리는 깨끗했다.
- 129는 `feature/129-review-validation`의 `cc81b37`로 커밋됐고 같은 원격 추적 브랜치가 있다. 130과 `origin/develop`에는 아직 포함되지 않았다.
- 로컬 `origin/develop`은 `2851ccd`이며 현재 130보다 4개 커밋 앞선다. 원격 추적 참조의 실제 최신 여부는 실행 시 fetch로 확인한다.
- 129는 DTO·검증기·검사 조회·테스트를 그대로 재사용한다. 권한, revision, 선택지, 검사 통과 조건을 130에 다시 구현하지 않는다.
- DB에는 결정마다 한 행을 추가한다. 과거 이력을 UPDATE·DELETE하지 않는다. 스키마·Migration·의존성·공통 인증 구조 변경을 계획하지 않는다.
- `review_decision`과 `proposal_item.review_status`, `held_item`은 별개다. 이력 저장만으로 후보 승인 완료·개별 보류 건 생성·실제 반영을 수행한 것으로 취급하지 않는다.
- 저장 Service는 `MANDATORY`로 호출자 트랜잭션을 요구한다. 130만 호출해 커밋하는 공개 쓰기 API를 만들지 않는다.
- API 054 `POST /api/v1/proposals/{id}/decisions`는 131의 상태 변경·반영·감사가 연결된 뒤 공개한다.
- 저장 시각은 주입받은 `Clock`의 `Instant.now(clock)`, 결정자는 `LoginAccount.id()`를 사용한다. 클라이언트가 결정자·시각을 지정하지 않는다.
- 기존 129의 HOLD 재요청 허용 및 모든 결정의 reason 필수 정책을 임의로 변경하지 않는다. 명세와 다르면 변경 전에 차이를 보고한다.

## 구현 전 확인할 계약

1. API 054·055 원문과 최신 ERD: API 055의 정확한 경로, 권한, 응답 필드, 페이지·필터·정렬·기본값, 오류 응답을 확인한다. 현재 연결의 Notion 검색에서 기준 API 페이지를 찾지 못했고 앞선 직접 조회는 404였다.
2. 내부 조회 투영 후보는 `id`, `proposalItemId`, `itemRevision`, `decision`, `selectedOptionId`, `reason`, `decidedBy`, `decidedAt`이다. 이것은 DB 기반 제안이며 공개 JSON 필드명이 확정됐다는 의미가 아니다. 응답의 revision 이름 등은 API 055에 맞춘다.
3. 명세에 조회 정렬이 없다면 `decided_at DESC, id DESC`를 구현 제안으로 공유한다. 페이지 기본값은 기존 Review의 0·20을 제안하되 명세 규칙으로 단정하지 않는다. 필터·size 상한을 임의로 추가하지 않는다.
4. 129 MR의 사람 리뷰에서 결정 정책·호출 계약 변경이 생겼는지 확인한다. 특히 같은 revision의 과거 성공 검사 허용, HOLD 재요청, reason 필수 조건을 확인한다.

## Review Focus

- 현재 후보 revision이 바뀌어도 과거 결정의 revision·선택지·사유가 보존되는가: Task 1·3의 DB 테스트로 검증한다.
- 검증 후 저장과 131의 후속 처리 사이에서 트랜잭션이 분리되지 않는가: Task 2의 외부 트랜잭션 필수·롤백 테스트로 검증한다.
- 선택지가 null이거나 다른 후보·revision에 속할 때 JDBC 매핑·검증이 올바른가: Task 1·2에서 검증한다.
- 같은 시각의 여러 이력과 다른 후보의 이력이 페이지에서 섞이거나 누락되지 않는가: Task 3에서 검증한다.
- 이력 INSERT만으로 중복 승인 방지가 완성됐다고 오인하지 않는가: Task 4의 완료 조건에서 131의 상태 전이·동시 승인 검증을 명시적으로 남긴다.

## Task 0: 129를 포함하는 작업 기반 준비

- [x] 현재 130 브랜치·작업 위치·사용자 변경을 확인하고 기존 계획 파일을 보존했다.
- [x] 129 `cc81b37`의 10개 변경 파일을 검증 후 작업 트리에 포함했다. 9개 코드·테스트 파일은 원본과 같고 검증 문서에 130 연동 설명만 추가했다.
- [x] 새 Git Commit이나 이력 변경을 만들지 않았다. 이후 129 MR 병합 상태에 맞춰 이력·MR 차이를 정리할 필요가 있다.
- [x] 브라우저로 API 054·055와 현재 운영 DB 문서를 확인했다. 자세한 출처와 초기 계획에서 바뀐 실행 방식은 실행 기록을 따른다.

## Task 1: 결정 이력 저장과 DB 매핑

**Files:** 아래 경로는 프로젝트 루트 기준이다.

- Create: `backend/src/main/java/com/axcore/platform/review/repository/ProposalDecisionWriteRepository.java`
- Create: `backend/src/test/java/com/axcore/platform/review/repository/ProposalDecisionRepositoryTests.java`

**Interfaces:**

- 저장: `long insert(ProposalItem item, ProposalDecisionRequest request, long decidedBy, Instant decidedAt)`.
- 저장 대상 식별자·revision은 검증기가 반환한 `item.getId()`·`item.getRevision()`을 사용한다. 요청에서는 decision·selectedOptionId·reason을 가져온다.

- [x] Testcontainers에서 `storesDecisionMetadataAndPreservesEarlierRows`: 동일 후보에 서로 다른 결정 이력을 넣고 사유·선택지·revision·결정자·고정 시각과 기존 행 보존을 확인하는 테스트를 먼저 작성한다. FK 대상 account·proposal_revision·proposal_option을 테스트 DB에 생성한다.
- [x] `storesNullSelectedOption`과 `rejectsOptionFromAnotherRevision`: nullable 선택지 저장 및 복합 FK의 후보·revision 제약을 확인한다. 실패가 예상되는 SQL은 독립 트랜잭션으로 격리한다.
- [x] `./gradlew.bat test --tests '*ProposalDecisionRepositoryTests'`로 미구현 실패를 확인한다.
- [x] 기존 JDBC 패턴으로 INSERT RETURNING id를 구현한다. nullable bigint는 타입을 명시해 바인딩하고 시각은 `Instant`로 저장한다. 임의 사유 절삭·공백 제거는 하지 않는다.
- [x] 같은 테스트를 실행해 테스트의 JDBC 조회로 저장 필드·기존 행 보존·제약 동작을 확인한다. 공개 DTO와 조회 저장소는 계약 확인 후 Task 3에서 추가한다.

## Task 2: 129 검증과 이력 저장을 같은 트랜잭션으로 연결

**Files:**

- Create: `backend/src/main/java/com/axcore/platform/review/service/ProposalDecisionHistoryService.java`
- 대신 실제 DB·Spring 프록시의 동작을 `ProposalDecisionHistoryTransactionTests`에서 검증한다. 별도 mock 전달 테스트 클래스는 추가하지 않는다.
- Create: `backend/src/test/java/com/axcore/platform/review/service/ProposalDecisionHistoryTransactionTests.java`
- Reuse: 129의 `ProposalDecisionValidator`, `ProposalDecisionRequest` 및 기존 테스트.

**Interfaces:**

- `long recordValidated(long proposalId, ProposalDecisionRequest request, LoginAccount account)`.
- Service 메서드는 `@Transactional(propagation = MANDATORY)`를 사용한다. `@Validated`와 인자 `@Min(1)`, `@NotNull @Valid`로 내부 호출에서도 DTO 유효성 검사를 보장한다. 계정 인증·인가 조건은 129 검증기를 호출해 적용한다.
- 131의 최상위 결정 Service가 트랜잭션을 시작하고 이 메서드를 호출한다. 반환값은 이력 ID이며 승인 완료 응답이 아니다.

- [x] `recordsAfterValidationUsingAuthenticatedActorAndClock`: 129 검증 결과의 후보와 로그인 account ID, 고정 Clock 시각이 저장소에 전달되는지 확인하는 테스트를 먼저 작성한다.
- [x] `doesNotInsertWhenValidationFails`: revision 충돌 등 대표 검증 실패에서 저장소 호출이 없는지 확인한다. 129의 기존 개별 정책 테스트를 그대로 재사용한다.
- [x] 내부 호출의 빈 사유·누락 revision·잘못된 decision을 Spring 프록시를 통해 호출해 Bean Validation이 INSERT 전에 동작하는지 확인한다.
- [x] 관련 테스트의 미구현 실패를 확인한 뒤 `validate → Instant.now(clock) → insert` 흐름을 구현한다. 상태 변경·반영·감사·재검사 접수는 추가하지 않는다.
- [x] DB 테스트 `requiresCallerTransaction`으로 트랜잭션 없는 호출이 거절되고 행이 생기지 않는지 확인한다.
- [x] DB 테스트 `rollsBackHistoryWhenCallerFails`로 외부 트랜잭션에서 저장 후 의도적인 예외를 발생시키고, 트랜잭션 종료 후 새 조회로 이력이 남지 않는지 확인한다. 이는 131의 실제 반영 실패 테스트를 대체하지 않는다.
- [x] DB 테스트에서 권한 없음·현재 revision 검사 실패·다른 revision 선택지가 저장되지 않는지 대표 경로를 확인한다. 기존 129 잠금 테스트와 함께 실행한다.

## Task 3: 후보별 결정 이력 조회 API 055

**Prerequisite 충족:** 브라우저로 API 055 원문을 확인했다. `GET /api/v1/proposals/{id}/decisions`와 응답·권한·페이지 입력 조건을 확정했다. 기본값·정렬은 구현 선택으로 구분해 문서화했다.

**Files:**

- Modify: `backend/src/main/java/com/axcore/platform/review/service/ProposalService.java`
- Modify: `backend/src/main/java/com/axcore/platform/review/controller/ProposalController.java`
- Create: `backend/src/main/java/com/axcore/platform/review/dto/ProposalDecisionResponse.java`
- Create: `backend/src/main/java/com/axcore/platform/review/repository/ProposalDecisionQueryRepository.java`
- Create if specified: `backend/src/main/java/com/axcore/platform/review/dto/ProposalDecisionSearchRequest.java`
- Create: `backend/src/test/java/com/axcore/platform/review/controller/ProposalDecisionHistoryApiTests.java`

**Interfaces:** 페이지 계약이면 Repository의 `Page<ProposalDecisionResponse> findByProposalItemId(long proposalItemId, Pageable pageable)`가 후보별 SELECT/COUNT를 수행하고, `ProposalService.getDecisions(long id, Pageable pageable)`가 후보 존재 확인 후 이를 호출한다. Controller는 `ApiResponse.ofPage`를 사용한다. 기존 Service의 읽기 전용 트랜잭션과 Controller 권한 검사를 따른다. 정확한 권한·입력·반환 형태는 API 055 원문이 우선한다.

- [x] 명세 경로의 MockMvc/Testcontainers 테스트를 먼저 작성한다. 결정 3종, 과거 revision, 선택지 null, 고정 UTC 시각·숫자 ID, 사유와 결정자, 다른 후보 이력 미노출을 확인한다.
- [x] 없는 후보는 404, 존재하지만 이력 없는 후보는 200 빈 목록인지 명세와 대조한 후 테스트에 고정한다. 401·403과 임시 비밀번호 제한, 잘못된 경로 ID·페이지 입력도 검증한다.
- [x] 페이지 계약이면 같은 `decided_at`의 여러 행·다음 페이지·범위 밖 페이지와 totalElements/hasNext를 확인한다. 명세 정렬과 고정 보조 정렬이 일치하는지 확인한다.
- [x] 미구현 API 테스트가 실패하는 것을 확인하고 기존 Controller/Service에 조회만 연결한다. 조회 때문에 후보 상태나 결정 이력이 바뀌지 않는지 확인한다.
- [x] 신규 조회 테스트와 기존 ProposalController/ProposalRevision API 테스트를 실행해 호환성을 확인한다. API 054 쓰기 경로는 추가하지 않는다.

## Task 4: 문서·회귀 검증·131 인계

**Files:**

- Modify: `docs/review-decision-validation.md` (129에서 포함된 문서).
- Create: `docs/review-decision-history.md`.
- Update: 이 계획의 체크박스와 실행 기록.

- [x] 130의 저장·조회 호출 계약, 필드·정렬·권한, 실제 테스트 결과와 미검증 사항을 문서화한다. API 명세 변경이 필요하면 원격 수정 승인과 별개로 필요한 수정을 보고한다. 스키마가 바뀌지 않았다면 ERD 변경 없음으로 보고한다.
- [x] JDK 21·Docker 환경에서 `backend/`의 `./gradlew.bat spotlessApply` 후 `./gradlew.bat spotlessCheck test --tests 'com.axcore.platform.review.*' --tests 'com.axcore.platform.global.*'`를 실행한다. 이후 최종 `./gradlew.bat build`를 실행한다. 실패·실행 불가는 원인과 미실행 범위를 기록하고 통과로 표현하지 않는다.
- [x] `git diff --check`와 변경 파일 목록을 확인하고 DB Migration·보안 설정·131 반영 로직이 섞이지 않았는지 확인한다. 테스트 수는 그 실행의 XML에서 집계한다.
- [x] 131에 아래 전체 경로를 인계한다: 최상위 트랜잭션 시작 → 입력 검증 → 130 recordValidated(129 검증·행 잠금 및 이력 INSERT) → 후보 상태 전이·실제 반영 → 감사 → 커밋. 처리 순서는 연동 계약에 따라 조정할 수 있지만 트랜잭션을 나누지 않는다.
- [x] 중복 APPROVE·동시 결정은 131의 상태 전이를 포함해 두 번째 요청이 거절되는지 검증한다. 130 저장 테이블에는 후보별 UNIQUE를 추가하지 않는다. 여러 번의 HOLD 이력을 임의로 중복 오류 처리하지 않는다.
- [x] 공개 API 054의 입력/권한/404/409/422와 승인·반영·감사 실패 롤백, 실제 CANDIDATE 검사 실행과 FE 연동은 131의 완료 조건으로 남긴다. 테스트 fixture의 성공 검사 행을 실제 실행부 구현으로 표현하지 않는다.
- [x] 130 완료 보고는 저장 모듈·조회 API의 검증 완료와 131의 쓰기 API 연동 미완료를 구분한다. 사람 리뷰·MR 병합 후 동작 확인도 별도 남은 항목으로 보고한다.

## 계획 작성 결과

- 저장소·129 커밋·Jira 130과 기존 트랜잭션·조회·테스트 패턴을 읽고 계획을 작성했다.
- 최초에는 계획 문서만 작성했고, 이후 사용자의 명시적인 구현 요청으로 아래 실행 기록을 추가했다.
- 계획 작성 당시에는 테스트·원문 확인을 완료하지 않았다. 구현 중 확인한 원문과 실행 결과는 아래에 구분한다.


## 실행 기록 (2026-11-04)

- Task 0: 현재 130 HEAD는 `2851ccd`. 기존 사용자 계획 파일을 보존했다. 129 원격 추적 커밋 `cc81b37`의 10개 파일 변경을 `git apply --check` 후 작업 트리에 반영했다. Git Commit·cherry-pick·merge·rebase·reset·Push는 하지 않았다. 따라서 129는 이력 통합이 아닌 미커밋 의존 코드로 포함되어 있다.
- 작업 위치: 사용자가 선택한 기존 `feature/130-review-decision-history`에서 진행했다. 새 worktree는 생성하지 않았다.
- Task 0 계약: 연결된 Notion 도구 대신 로그인된 브라우저로 [API 055](https://app.notion.com/p/405bdf14d2d8816ea256f4ccc0933ebe)와 [API 054](https://app.notion.com/p/405bdf14d2d8813dbde3d11097ddd5df)를 읽었다. API 055는 GET `/api/v1/proposals/{id}/decisions`, ADMIN, page/size, 8개 응답 필드와 PageMeta다. 입력 최소값은 id=1/page=0/size=1이다. 기본값 0·20과 시각·ID 내림차순은 구현 기본값으로 문서화했다.
- ERD: 기존 `.ai/references.md`의 운영 ERD v1 링크가 휴지통에 있어 기준에서 제외하고 사용자에게 알렸다. ERD 상위 페이지에서 [현재 운영 DB](https://app.notion.com/p/401bdf14d2d880e6b8ebdaeec27fa921)를 확인했다. `review_decision`의 목적·기존 구조를 재사용하고 스키마를 변경하지 않았다. 저장소 기준 링크 정리가 필요하다.
- 기준 검증: JDK 21·Docker에서 129를 포함한 기존 review/global 테스트 성공(1분 41초).
- Task 1·2: 테스트를 먼저 작성해 저장 Repository·Service 미구현으로 인한 컴파일 실패를 확인했다. 구현 후 저장·외부 트랜잭션 필수·내부 입력 검증·검증 실패 무저장·호출자 실패 롤백 테스트 성공(41초). mock-only Service 전달 테스트 대신 실제 DB·Spring Bean 프록시로 검증했다.
- Task 3: 신규 조회 API 테스트 23건 중 21건이 미구현 경로의 404로 실패하고 기존 보안 필터 경로 2건은 통과함을 확인했다. 조회 구현 후 23건 모두 성공(37초).
- Task 4: 저장·조회·131 인계 문서를 작성했다. 전체 회귀·최종 build·별도 읽기 전용 검토의 결과는 완료 시 기록한다.

- 최종 관련 검증: `backend/`에서 `./gradlew.bat spotlessApply spotlessCheck test --tests 'com.axcore.platform.review.*' --tests 'com.axcore.platform.global.*'` 성공(1분 41초). XML 23개 클래스·299건, 실패·오류·건너뜀 0. 로그 `.gradle-130-related.log`.
- 최종 전체 검증: `backend/`에서 `./gradlew.bat build` 성공(3분 21초). XML 70개 클래스·663건, 실패·오류·건너뜀 0. 포맷 검사와 전체 테스트 포함. 로그 `.gradle-130-build.log`.
- 신규 130 테스트 50건: Repository 7, HistoryTransaction 20, HistoryApi 23. 기존 129 테스트도 전체 실행에 포함됐다.
- 별도 읽기 전용 코드 검토: Critical·Major·Minor 발견 없음. 검토자는 테스트를 따로 실행하거나 외부 명세를 재조회하지 않았으며, 실행 검증·명세 확인은 주 작업에서 수행했다.
- `git diff --check` 및 미추적 Java·문서의 공백 확인 성공. 129의 10개 파일이 모두 포함됐고, 코드·테스트 9개는 cc81b37과 같으며 검증 문서만 130 인계 설명이 추가됐다.
- 미실행: API 054 공개 호출·실제 CANDIDATE 실행·131 반영/감사 서비스·실제 FE 연동·운영 배포. 130의 내부 트랜잭션 롤백 테스트는 실제 반영/감사 실패 롤백의 대체 검증이 아니다.
- 남은 사람 확인: 129 MR 리뷰 결과 반영, 130 사람 리뷰·MR 병합 후 동작 확인, API 055의 페이지 기본값/정렬 명세 보완, 저장소 기준 문서의 오래된 ERD 링크 정리.
- Commit·Push·Merge·원격 MR/Jira/Notion 변경은 하지 않았다. 구현과 문서는 현재 130 브랜치의 미커밋 작업 트리에 보존했다.
