# 관리자용 객체 유형 관계도 (API 042, Jira 231)

`GET /api/v1/ontology/model-graph`는 업무 데이터 화면에 표시할 객체 유형과 관계 유형을 돌려준다. 요청 본문과 조회 파라미터는 없다. 세션이 없으면 401, ADMIN 권한이 없거나 임시 비밀번호를 변경해야 하면 403이다.

| 응답 필드 | 내용 |
| --- | --- |
| `data.nodes` | 객체 유형의 `id`, `code`, `name` |
| `data.edges` | 관계 유형의 `id`, `sourceTypeId`, `targetTypeId`, `code`, `name`, `cardinality`, `relationCount`, `activeLinkCount` |

노드와 선은 각각 ID 순이다. 연결이 없는 관계 유형도 두 건수가 0인 선으로 표시한다. `relationCount`는 저장된 전체 연결 수로 비활성 연결도 포함한다. `activeLinkCount`는 `object_link.status`가 `ACTIVE`인 건수다. 연결의 생성 근거나 양 끝 객체의 원천 삭제 상태와 관계없이 저장된 연결의 상태를 기준으로 센다.

조회는 플랫폼 DB의 한 스냅샷을 사용한다. 조회 중에 관계 유형이 추가되더라도 선의 출발·도착 ID는 응답에 포함된 노드를 가리킨다. 수집과 관계 재계산의 결과는 DB에 반영된 뒤 다음 조회에 나타난다.

노드 조회는 JPA를 사용한다. 관계 건수 집계는 Entity가 없는 `object_link`의 기존 Repository 방식에 맞춰 `JdbcClient`를 사용한다. 집계에 필요한 테이블과 제약은 기존 ERD에 정의돼 있다. [Notion API 042 초안](https://app.notion.com/p/405bdf14d2d88194b821f616c6f99af3)
