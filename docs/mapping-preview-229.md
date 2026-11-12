# S15P31C106-229 변환 미리보기

관리자 → 데이터 연결 → 원천 선택 → 원천 매핑 → 객체 매핑 등록/편집.
속성 변환은 객체 매핑 상세 → 속성 매핑 등록/편집 안에서 확인한다.

## 2026-11-12 API 연결

- SourcesPage에서 mappingPreviewApi를 주입한다. 040 접수와 102 상태·결과 조회를 실제 공통 HTTP 클라이언트로 호출한다. 세션·CSRF는 기존 클라이언트를 사용한다.
- 040: POST /api/v1/ontology/source-resources/{id}/mapping-previews. 저장 전 초안의 objectTypeId, keyColumns, rowFilter, propertyMappings와 rowLimit(1~20, 기본 20)을 보낸다. 변환 단계는 저장 API와 같은 {op,arg}이며 저장용 ID·rowVersion·isActive는 보내지 않는다.
- 102: GET /api/v1/ontology/source-previews/{previewId}. 접수 ID·MAPPING_PREVIEW 종류·상태·만료 및 결과 구조를 확인한다. SAMPLE이나 잘못된 응답은 표시하지 않는다.
- 접수 후 2초마다 대기·실행을 조회하고 완료·실패 때 멈춘다. 조회 오류는 새 작업을 접수하지 않고 GET만 재시도한다. 만료·404는 결과를 지우고 다시 요청할 수 있게 한다.
- 기존 속성 매핑은 모든 페이지를 읽고 편집 항목을 대체하거나 새 초안을 추가한다. 초안을 바꾸거나 편집 창을 닫으면 이전 결과와 늦은 응답을 표시하지 않는다.
- 미리보기와 행 수 입력의 Enter는 실제 매핑 저장을 호출하지 않는다. 저장 버튼은 기존대로 동작한다.
- 표본은 컴포넌트 안에서만 보관하며 Query 캐시·브라우저 저장소에 넣지 않는다. 기존 행 조건·문서 경로 편집 제한을 유지한다.

## 표시 정책과 제공되지 않는 정보

[102 원문](https://app.notion.com/p/406bdf14d2d8817fa984ccd997fb1d8b)은 관리자 전용이고 별도 가림 없이 제공하며 금지 컬럼은 원천 권한으로 막는다고 정한다. 허용된 sourceKey·속성 값을 표시하고 JSON·NULL·빈 문자열·0·false를 구분한다.

실제 DTO의 제약은 다음과 같다.

- objects[]에는 sourceKey, displayName, properties만 있다. 변환 전 값·원천 컬럼과 속성의 대응·원천 행 번호는 **미제공**으로 표시한다. 키 오류로 객체가 빠질 수 있으므로 objects 배열의 순서를 원천 행 번호로 쓰지 않는다. 객체 번호는 화면의 결과 순서일 뿐이다.
- rowErrors[]의 rowIndex(0부터)는 화면에서 1부터 표시한다. 원천 column과 알려진 실패 transform은 표시한다. 대상 속성·단계 번호·오류 코드는 미제공이다.
- message에는 원천 값이 들어갈 수 있다. 키 누락·자료형 오류의 정해진 문구만 허용하고, 그 외에는 알려진 변환 함수에 맞춘 안내로 바꾼다. 임의 오류 원문·SQL·드라이버 메시지는 화면 모델에도 전달하지 않는다. FAILED의 errorMessage와 HTTP 오류 원문도 표시하지 않는다.
- 다른 원천 표본을 조회해 전후 행을 임의로 결합하지 않으며 프론트에서 변환 엔진을 구현하지 않는다. Jira의 완전한 전후 비교 완료 기준에는 백엔드의 원본 값과 행 대응 근거 제공이 추가로 필요하다.

## 기준과 검증

- [040 원문](https://app.notion.com/p/405bdf14d2d8816bbd89e4d25e4783dd), [102 원문](https://app.notion.com/p/406bdf14d2d8817fa984ccd997fb1d8b), 실제 DTO·Controller·배치 처리기 및 [백엔드 구현 설명](mapping-preview.md)을 대조했다.
- 040 원문의 NOTIFY 설명과 실제 배치의 2초 polling 차이는 기존 백엔드 문서에 기록되어 있다. 이번 프론트 연결은 작업 전달 방식을 변경하지 않는다.
- previewApi.test.tsx: 실제 HTTP 어댑터의 초안·CSRF·joined, 잘못된 응답·행 수·HTTP 실패·만료, 값 구분·원문 비노출 및 화면의 객체/속성 요청·저장 미호출·GET 재시도를 검증한다. MSW 기반이며 운영 데이터 시험은 아니다.
- 기존 MappingPreview·초안·매핑 저장 회귀 테스트도 유지한다. 개발 모드의 MSW는 실제 변환을 수행하지 않는 고정 모의 표본을 반환한다.

API·DB·인증 구조 변경 없음. 사람의 리뷰·병합 및 배포 후 실제 원천을 사용한 시험 변환 확인은 별도다.
