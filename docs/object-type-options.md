# 객체 검색용 유형 선택지 (Jira 347)

객체 탐색 화면의 검색 조건 드롭다운에 쓸 객체 유형 목록이다. 042 모델 관계도(Jira 231)에서 떼어 냈다.

| API | 요청 | 결과 |
| --- | --- | --- |
| (번호 미정) | `GET /api/v1/objects/types` | 200 `data: [{id, code, name}]`, `meta` 없음 |

- 로그인한 사용자면 누구나 부를 수 있다. 세션이 없으면 401, 비밀번호를 바꿔야 하는 계정이면 403이다(다른 조회 API와 같음).
- 조회 범위는 객체 검색(076)과 같은 `DataAccessPolicy.visibleObjectTypeIds()`다. 076에서 결과가 나올 수 있는 유형만 선택지에 나온다. 볼 수 있는 유형이 없으면 빈 목록이다.
- 이름순, 이름이 같으면 ID 순서다. 페이지를 나누지 않는다(유형은 수십 개 수준).

## 왜 따로 두나

- 화면은 유형 선택지를 042 응답의 노드에서 꺼내고 있었다. 042는 유형별 객체 수와 관계까지 계산하는 무거운 응답이고, 042가 없거나 실패하면 화면은 객체 목록 첫 페이지 20개에 나온 유형만 선택지로 보여 준다.
- 기존 `GET /api/v1/ontology/object-types`는 정의 관리용이라 관리자 전용이다. 일반 사용자의 탐색 화면에는 쓸 수 없다.
- 검색 조건(드롭다운)은 이 API, 관계도는 042로 나누면 관계도가 늦거나 실패해도 검색은 동작한다.

## 구현

- `ObjectSearchController.types` → `ObjectTypeOptionService.options` → `ObjectTypeRepository.findByIdInOrderByNameAscIdAsc`(JPA).
- 유형별 객체 수, 관계, 속성은 넣지 않는다(042·043이 맡는다).

## 시험

`ObjectSearchApiTests`

- `typeOptionsFollowSearchScopeAndSortByName`: VIEWER는 허용된 접근 그룹의 유형만 이름순으로, ADMIN은 모두, 범위가 없는 역할은 빈 목록.
- `typeOptionsRequireUsableSession`: 세션 없음 401, 비밀번호 변경이 필요한 계정 403.

## 남은 일

- 노션 API 명세에 새 API를 추가한다(번호는 명세 담당과 정함).
- 탐색 화면(`frontend/src/features/explorer/ExplorerPage.tsx`)의 유형 선택지를 이 API로 바꾼다(FE).
