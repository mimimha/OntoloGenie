# 346 연결 규칙 디자인 검증

## 변경 범위

- 목표: 사용자 제공 목표 스크린샷 6장 및 Chrome에 열린 Figma Make 목업의 연결 규칙 화면.
- Jira: https://ssafy.atlassian.net/browse/S15P31C106-346
- 브랜치: fix/346-rules-design (origin/develop 0479677 기준).
- 상태별 건수 탭, 객체 속성·매핑 구분, 전체 후보 검색, 왼쪽 후보 카드와 오른쪽 상세 패널.
- 상세의 규칙 내용·검사 결과·제안 이유 탭. 기존 검사, 검토 결정, 대안 선택, 이력 이동을 재사용한다.
- 분석 실행 버튼과 직접 관계 제안 버튼. 최근 분석·재분석·기존 테스트 분석 관리 기능은 분석 관리 메뉴로 이동했다.
- 적용 후 문제의 상태별 버튼과 기존 상세·상태 변경·재검증 흐름.
- 연결 규칙 경로에 한정한 배경, 헤더, 메뉴, 버튼, 다이얼로그 및 입력 스타일.
- 로고는 사용자 제공 목표 이미지의 로고 형태를 두 색 SVG 경로로 복원했다. Figma 원본 SVG를 내려받은 파일은 아니다.
- API 계약, DB 및 배포 설정 변경 없음.

## 초기 구현 검증

2026-11-11 KST, frontend 디렉터리에서 실행했다.

- `npx vitest run --reporter=json --outputFile=.review-tests.json`: 39개 파일, 510개 테스트 통과. 보고서는 로컬 `test-results/frontend-review-346.json`에 보관한다.
- 주요 시나리오: 후보 선택·상세 근거, 상태·유형·검색 조합과 빈 결과, 뒤로·앞으로 이동 시 필터 복원, 두 번째 서버 페이지의 후보까지 집계·검색, 기존 분석·검사·승인·보류·문제 처리·권한 검증.
- `npm run build`: 타입 검사와 프로덕션 빌드 통과. 기존 500 kB 초과 번들 경고가 있다.
- `npm run lint`: 통과.
- `npm run format:check`: 통과.
- `git diff --check`: 통과.

## 화면 확인과 제약

- Chrome에 열려 있던 Figma Make에서 상태 탭, 후보 상세 구조 및 실제 색상·패딩·폰트·테두리 값을 확인했다.
- 로컬 개발 서버에서 개발용 MSW 관리자 계정으로 로그인해 그라데이션·헤더·상태 탭·검색·후보 카드와 상세 패널이 표시되는 화면을 확인했다.
- 이후 Chrome의 현재 URL을 안전하게 판별할 수 없다는 사유로 컴퓨터 사용이 중단됐다. 최종 SVG 로고 교체·계정 메뉴 정렬 및 스타일 파일 분리 이후의 화면 재확인과 모바일 브라우저 검증은 미실행이다.
- 로컬 개발용 모의 API를 사용했다. 실제 배포 서비스에서 수정 후 분석·승인·보류·재검증을 실행한 기록이 아니다.
- 검색·건수는 기존 페이지 API에서 전체 후보를 읽어 계산한다. 후보가 크게 증가하면 서버 검색·집계 API를 별도 검토할 필요가 있다.
- 브랜치 푸시는 배포 완료를 뜻하지 않는다. 병합·배포와 배포 후 시각 검증은 후속 작업이다.

## Major 지적 수정 검증

- 지적: 새 분석을 시작해도 기존 `origin`, `applyStatus`, `view`가 남아 AI 후보가 숨겨지거나 문제 보기에 머물렀다.
- 수정: 새 분석 접수 성공 시 조회 주소를 새로 만들고 분석 ID와 실제 합류 여부만 남긴다. 접수 실패 시 기존 조회 조건과 문제 보기를 유지한다.
- 회귀 테스트: 직접 추가 출처, 반영 완료 상태, 문제 보기, 여러 조건과 실행 합류, 접수 실패를 검증한다.
- 수정 전 `npx vitest run src/features/analysis/AnalysisPanel.test.tsx -t '새 분석'`: 성공 경로 4건 실패로 기존 문제를 재현했고 실패 경로 보호 1건은 통과했다.
- 수정 후 같은 명령: 5건 모두 통과했다. 보고서는 로컬 `test-results/346-analysis-before.json`, `test-results/346-analysis-after.json`에 보관한다.
- 수정 후 전체 `npx vitest run --reporter=json --outputFile=../test-results/frontend-review-346-fixed.json`: 39개 파일, 515개 테스트 통과.
- 수정 후 `npm run build`, `npm run lint`, `npm run format:check`, `git diff --check`: 모두 통과. 빌드의 500 kB 초과 번들 경고는 그대로 있다.
- 독립 서브에이전트 재리뷰: 이전 Major 해결 확인, 추가 Critical·Major·Minor 각 0건. 리뷰어가 `npx vitest run src/features/analysis/AnalysisPanel.test.tsx`를 직접 실행해 20건 통과를 확인했다. AI 리뷰이며 사람의 리뷰·승인을 대신하지 않는다.

## develop 병합 충돌 해결

- 반영 대상: `origin/develop`의 `976aaf45cfaf0ad16c0d631db777041f44198a87`. 기존 작업 이력을 유지하는 merge 방식으로 반영했다.
- 충돌 파일은 `frontend/src/features/analysis/AnalysisPanel.tsx` 하나였다. `develop`의 `01386e4` 주석 정리와 이번 브랜치의 `compact` 옵션·여러 줄 함수 선언 변경이 같은 구간에 겹쳤다.
- 해결: `develop`의 정리된 주석과 작업 브랜치의 `compact` 옵션 및 화면 구성을 함께 보존했다. 다른 `develop` 변경은 Git이 자동 병합한 내용을 유지했다.
- 병합된 코드에서 프론트엔드 전체 테스트 39개 파일·515건 통과. 보고서: 로컬 `test-results/frontend-review-346-merged.json`.
- 병합 후 `npm run build`, `npm run lint`, `npm run format:check`, `git diff --cached --check` 통과. 기존 번들 크기 경고는 동일하다.
- 수동 충돌 해결 범위는 프론트엔드이며 backend·AI 테스트는 이번 병합에서 재실행하지 않았다. MR의 develop 병합과 배포는 수행하지 않는다.
- 첫 푸시 후 `develop`에 커밋 4개가 더 추가되어 `1450ac7ba7f39fffd9b6964aceef732c22a400e2`까지 한 차례 더 merge했다. AI 파일 3개와 백엔드 테스트 1개가 충돌 없이 원본 그대로 반영됐으며 프론트엔드 파일은 앞서 검증한 코드와 동일하다.
- 추가 반영 후 backend에서 `./gradlew.bat test --tests '*AgentObjectEvidenceToolsTests' --offline`: 테스트 2건 통과. AI의 `python -m pytest tests/test_reanalysis_inputs.py -q`는 로컬 Python에 pytest가 설치되지 않아 실행하지 못했다.
- 독립 통합 리뷰 2회: 충돌 해결 및 추가 자동 통합에서 Critical·Major·Minor 각 0건. 미해결 충돌과 diff 형식 오류가 없음을 확인했다. AI 리뷰는 사람의 MR 승인·병합을 대신하지 않는다.
