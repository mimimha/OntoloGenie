# 229 API 연결 검증 — 2026-11-12

브랜치: `feature/229-mapping-preview-api`, 기준: `origin/develop` (`b1b93f0`).

## 실행 결과

| 실행 위치 | 명령·방법 | 결과 |
| --- | --- | --- |
| frontend | `npm test -- --run` | 최종 40파일 639개 통과 |
| frontend | `npm test -- --run src/features/mapping` | 수정 후 4파일 64개 통과 |
| frontend | `npm run build` | 성공. 기존 500kB 초과 청크 경고 |
| frontend | `npm run lint` | 성공 |
| frontend | `npm run format:check` | 성공 |
| backend | `./gradlew.bat test --tests '*MappingPreviewApiTests' --offline` | 실제 Testcontainers DB를 사용하는 5개 테스트 통과, 실패·오류·skip 0 |
| root | `git diff --check` | 통과 |
| Chrome | 로컬 Vite·MSW 화면 | 객체 편집 → 요청 → RUNNING → 결과 1건·행별 오류 1건 표시. 결과 순서와 원천 행 번호를 구분하고, 정제한 오류와 미제공 값을 표시 |

브라우저 화면은 실제 HTTP 어댑터를 거치는 개발용 고정 표본이다. 원천 변환 엔진이나 운영 데이터 검증을 뜻하지 않는다. [화면 증거](local-preview.jpg).

HTTP 어댑터 테스트는 객체·속성 편집 경로에서 저장 API 미호출, 중복 클릭 1회 접수, 조회 실패 후 GET만 재시도, 권한·입력·충돌·만료 오류, 잘못된 ID·종류·응답, NULL·빈 문자열·0·false·JSON 및 오류 원문 비노출을 확인한다. 기존 테스트는 초안 변경·창 닫기·늦은 응답·행 수 Enter가 저장을 실행하지 않는 흐름을 검증한다.

## 독립 AI 리뷰

- 첫 리뷰: Critical 0 / Major 0 / Minor 2.
- Minor 1: 접수 전 404가 접수된 결과의 만료로 안내되던 문제. 접수 후 조회 404만 만료로 분류하도록 수정하고 회귀 테스트 추가.
- Minor 2: 개발 mock이 1행 요청에도 2행 오류를 반환하던 문제. 요청 행 수를 저장·적용하고 회귀 테스트 추가.
- 수정 후 별도 에이전트 재리뷰: Critical 0 / Major 0 / Minor 0. 독립 실행한 어댑터·미리보기 테스트 2파일 48개 통과.
- AI 리뷰는 사람의 리뷰와 승인을 대신하지 않는다.

## 남은 확인

- 배포 후 실제 원천·배치로 요청부터 완료까지 시험 변환, 업무 객체·연결·매핑 미저장 확인.
- 실제 비관리자 브라우저 세션의 권한 차단 확인. 서버 401·403·CSRF는 API 테스트로 확인했다.
- 서버 응답에 없는 변환 전 값·객체의 원천 행 대응·오류의 단계 번호·대상 속성·코드는 미제공이다. 완전한 전후 비교에는 백엔드 근거 제공이 필요하다.
- 사람의 리뷰·MR 병합·병합 후 확인은 미실행이다. API·DB·인증 구조는 변경하지 않았다.
