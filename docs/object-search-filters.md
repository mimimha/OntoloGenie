# 객체 검색 속성·기간 조건 (S15P31C106-358)

API 076 `GET /api/v1/objects`에 선택 조건을 추가한다. 기존 요청·응답, 권한 범위와 ID 순서 정렬은 유지한다. Agent의 목록 조회 도구(359)와 질문 해석(360)은 이 변경 이후 별도로 연결한다.

## 요청

기존 `q`, `objectTypeId`, `status`, `page`, `size`에 아래 조건을 더한다. 속성·기간 조건을 사용하면 `objectTypeId`가 필수다. 유형을 지정하지 않은 기존 검색은 그대로 동작한다.

| 파라미터                   | 형식                                               | 의미                                        |
| -------------------------- | -------------------------------------------------- | ------------------------------------------- |
| `propertyValues[속성코드]` | 문자열 값, 여러 개 가능                            | 해당 유형의 속성 값과 일치. 여러 속성은 AND |
| `dateProperty`             | 속성 코드                                          | 기간으로 검색할 DATE 또는 DATETIME 속성     |
| `from`                     | DATE: `YYYY-MM-DD`, DATETIME: 시간대 포함 ISO 8601 | 시작 경계 포함. 생략 가능                   |
| `to`                       | DATE: `YYYY-MM-DD`, DATETIME: 시간대 포함 ISO 8601 | 끝 경계 포함. 생략 가능                     |

`dateProperty`를 쓰면 `from`과 `to` 중 하나 이상을 지정한다. 날짜만 있는 DATE와 시간대가 있는 DATETIME을 구분하며, DATETIME의 날짜만 입력하거나 시간대를 생략하면 거절한다. DATETIME 비교는 같은 순간을 가리키는 서로 다른 UTC 오프셋을 같게 취급한다. 기존 `status`는 객체의 `ACTIVE`·`DELETED_AT_SOURCE` 상태이며, 업무 상태는 `propertyValues[status]`처럼 따로 지정한다.

예시(속성 코드는 대상 유형의 실제 정의에 맞춘다):

```text
GET /api/v1/objects?objectTypeId=123&propertyValues[status]=진행&propertyValues[line]=1라인&dateProperty=planned_start&from=2026-10-01&to=2026-10-31&page=0&size=20
```

실제 URL에서는 대괄호·한글·DATETIME의 `+` 등을 URL 인코딩한다. 날짜는 0001~9999년 범위이며 DATETIME은 UTC로 환산한 날짜에 적용한다. 숫자는 자료형에 맞게 비교하므로 DECIMAL의 `1200`, `1200.00`, 숫자 문자열 저장 값은 동일하다. BOOLEAN은 `true`·`false`만 받는다. STRING은 공백을 포함한 정확한 값으로 비교하며, 빈 문자열과 값 없음(null·속성 누락)을 구분한다. JSON 속성의 일치 검색과 TIME의 기간 검색은 이번 범위에 포함하지 않는다.

## 검증과 권한

- 해당 유형에 정의되지 않은 속성, 잘못된 자료형·날짜·기간 역전, 유형 없는 조건, 경계 없는 기간은 `400 VALIDATION_FAILED`다.
- 민감 속성을 볼 권한이 없으면 그 속성의 값·기간 조건을 `403 FORBIDDEN`으로 거절한다. 결과가 0건이어도 허용하지 않는다.
- 볼 수 없는 유형과 존재하지 않는 유형은 기존 검색처럼 빈 목록과 0건을 반환한다. 이 경우 속성 존재 여부를 검사해 드러내지 않는다.
- 검색어·조건·객체 상태·조회 권한을 목록과 `totalElements`에 함께 적용한 뒤 페이지를 나눈다.
- JSON 속성 키와 값은 SQL 파라미터로 바인딩한다. 정의 변경 등으로 남은 잘못된 저장 값은 형 변환 오류 대신 검색 대상에서 제외한다.
- 스키마·Entity·Migration 변경은 없다.

## 명세와 검증

로컬 OpenAPI 3.1 변경 명세는 [object-search-filters.openapi.yaml](object-search-filters.openapi.yaml)이다. Notion API 076 원문은 연결 계정에서 접근되지 않아 수정하지 않았다. API 담당 정유림과 요청 형식을 검토하고 Notion 원본에 반영해야 한다.

`ObjectSearchApiTests`에서 복합 속성·기간 조건, 페이지별 건수, DATE 경계, DATETIME 오프셋, 숫자·불리언·시간 비교, 잘못된 입력, 민감 속성 권한, 숨겨진 유형과 기존 검색을 확인한다.
