# 수동 수집과 실행 이력 화면 (S15P31C106-120)

## 확인한 계약과 제공 상태

2026-11-07 최신 develop(ae45b2c), 운영 [Swagger](https://ontologenie.com/swagger-ui/index.html), 아래 Notion 원문을 확인했다. 운영 Swagger와 저장소 Controller에는 073·074·075가 아직 없다. 배치 SyncRunJob의 존재와 HTTP API 제공은 구분한다.

- [073 원천 데이터 수동 수집 요청](https://app.notion.com/p/405bdf14d2d881c1ae77f61a7fdb01f9): POST /api/v1/sources/{id}/sync-runs, 선택 sourceResourceIds, 202 접수.
- [074 원천 수집 이력 조회](https://app.notion.com/p/405bdf14d2d881e9a77bed57b622c906): GET 같은 경로, status·triggerKind·from·to·page·size. 기본은 CDC 제외이며 meta.cdcLastAppliedAt을 표시한다.
- [075 수집 상태·건수·실패 사유](https://app.notion.com/p/405bdf14d2d88116b743f0525b5038ad): GET /api/v1/sync-runs/{id}, 실행 상태와 테이블별 방식·건수·오류.

## 구현과 연결 경계

데이터 연결의 수집 이력 탭에 전체 또는 선택 테이블 수동 요청, 접수 결과, 기존 실행 합류 안내, 상태·원인·기간 필터, 페이지 이동, 상세·테이블별 결과를 구현했다. 조회 중·빈 결과·오류를 분리한다. 날짜 범위는 시작 포함·종료 미포함이며 사용자의 현지 시각을 ISO 시각으로 변환한다.

중복 여부는 테이블별 서버 응답을 따른다. 원천에 실행 중 작업이 있다는 이유로 다른 테이블 요청까지 막지 않는다. 전체 합류 시 최상위 syncRunId=null을 처리하고 resources[].syncRunId 각각을 열 수 있다. 수동 요청의 연속 클릭은 차단하며 자동 재시도하지 않는다. 실행 상세·목록은 대기/실행 중인 경우 2초 간격으로 조회하고 오류 시 자동 조회를 멈춘다. 테이블 상태가 QUEUED여도 실행이 끝날 수 있으므로 진행 여부는 실행 상태로 판단한다.

현재 SourcesPage는 SourceSyncRuns에 api를 전달하지 않는다. 실제 호출 없이 미제공 안내와 비활성 버튼을 표시하며 가짜 성공·이력을 표시하지 않는다. 어댑터 sourceSyncApi는 명세 기반 HTTP 연결 함수로 준비하고 테스트에서만 주입했다. 권한은 기존 ADMIN 관리 라우트로 제한하며 실제 서버 인가는 API 제공 후 검증해야 한다.

## API 제공 후 남은 작업

1. 운영 Swagger·Notion과 실제 응답을 다시 대조한다. 선택 대상의 활성 매핑 검증, 권한, 필드와 오류 계약을 확인한다.
2. SourcesPage에서 sourceSyncApi를 주입해 073·074·075를 연결한다. 필요하면 테이블 표시명·원천 목록의 최근 수집 상태/시각도 실제 응답과 연결한다.
3. 실제 백엔드로 전체·부분 합류, 여러 기존 실행, 403·404·409·422, QUEUED→RUNNING→성공/실패, CDC 필터·마지막 반영 시각, 페이지·기간을 검증한다.
4. 팀원 리뷰와 MR 병합 후 동작 확인을 완료한다. 이 전에는 Jira 120 전체 완료로 판단하지 않는다.

API·DB 계약은 변경하지 않았다. 프론트 테스트는 모의 HTTP 응답 기반이며 실제 수집 성공 증거를 대신하지 않는다.

## 실행한 검증

- `npm test -- --run src/features/sources src/features/auth/RoleAccess.test.tsx`: 3개 파일, 56개 통과.
- `npm test -- --run`: 31개 파일, 369개 통과.
- `npm run build`: 통과. 500 kB 초과 청크 경고가 남아 있다.
- 변경 TS/TSX ESLint 및 변경 파일 Prettier 검사: 통과.
- 실제 073·074·075 연동과 운영 수집은 API 미제공으로 미실행.
