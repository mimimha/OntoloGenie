# 승인 후보 반영 서비스 — S15P31C106-189

승인 흐름(131)이 부르는 반영 서비스의 계약이다. 관리자가 후보를 승인하면 131이 트랜잭션을 열고 129 검사, 130 결정 이력, 이 서비스, 후보 상태 변경, 감사 기록을 차례로 실행한다. 모두 같은 트랜잭션이라 함께 커밋되거나 함께 롤백된다(FN-REV-06, 결정 U6).

## 호출

```java
ApplyResult result = proposalApplyService.apply(new ApplyCommand(
        proposalItemId, itemKind, origin, payload, decidedBy));
```

`ProposalApplyService.apply`는 `@Transactional(propagation = MANDATORY)`다. 트랜잭션 밖에서 부르면 `IllegalTransactionStateException`이 난다. 따로 커밋하지 않는다.

## 입력 `ApplyCommand`

| 필드 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `proposalItemId` | `proposal_item.id` | 만든 행의 `proposal_item_id`. `origin='AI_PROPOSAL'`이면 DB가 필수로 요구한다 |
| `itemKind` | `proposal_item.item_kind` (`ProposalItemKind`) | 반영기 선택 |
| `origin` | `proposal_item.origin` (`ProposalOrigin` AI·MANUAL) | 만든 행의 `origin`(AI_PROPOSAL·MANUAL) |
| `payload` | 선택지를 골랐으면 `proposal_option.payload`, 아니면 현재 revision의 payload | 반영할 내용. 정의는 code로 가리킨다 |
| `decidedBy` | 승인한 계정 ID | 만든 행의 `created_by`, 문서 연결의 `confirmed_by` |

code를 DB ID로 바꾸는 일은 이 서비스가 한다. 아직 승인되지 않은 후보는 찾지 않는다.

## 결과 `ApplyResult`

| 필드 | 내용 |
| --- | --- |
| `targetTable`, `targetId` | 대표로 만든 행 |
| `heldItemIds` | 관계 규칙을 반영하며 만든 보류 건. 커밋 전에 이미 `held_item`에 있다 |
| `reloadResourceIds` | 매핑을 반영해 전체 다시 읽기(`loaded_lsn = NULL`)를 요청한 원천 자원. 객체는 수집 배치가 만든다. 다시 읽는 동안에도 CDC 이벤트는 계속 반영된다(V11) |
| `summary` | 감사 기록 `result`에 넣을 요약 |
| `changes` | 바뀐 행의 전후 내용(`List<ChangeEntry>`). 감사 기록 `changes`에 그대로 넣는다 |

## 후보 종류별 반영

| 종류 | 바뀌는 행 | 단계 |
| --- | --- | --- |
| OBJECT_TYPE | `object_type` 추가 | 1 |
| PROPERTY | `property_definition` 추가 | 1 |
| LINK_TYPE | `link_type` 추가 | 1 |
| OBJECT_MAPPING | `object_source_mapping` 추가, `source_resource.loaded_lsn` 비움 | 1 |
| PROPERTY_MAPPING | `property_source_mapping` 추가, `source_resource.loaded_lsn` 비움 | 1 |
| LINK_RULE | 이전 `link_mapping` 개정본 RETIRED, 새 개정본·비교 항목 추가, `object_link` 재조정, `held_item` 추가 | 2 (186·189) |
| DOCUMENT_LINK | `object_link`(origin=DOCUMENT) 추가 | 3 (181) |

반영기가 없는 종류는 `APPLY_KIND_NOT_SUPPORTED`로 거절한다. 1단계(정의·매핑 5종)와 2단계(LINK_RULE)는 반영기가 있고, DOCUMENT_LINK는 아직 이 상태다(S15P31C106-329). 호출 방식은 바뀌지 않는다.

선행 후보가 먼저 반영돼야 한다: 객체 유형 → 속성·관계 유형 → 객체 매핑 → 속성 매핑 → 관계 규칙.

### AI 묶음 후보 (API-105)

아래는 반영기(S15P31C106-189)가 따르는 기준이다. OBJECT_TYPE 묶음은 1단계, LINK_RULE 묶음은 2단계에서 구현했다.

AI 제안기는 관리자가 판단하는 단위로 후보를 묶어 보낸다. 표 하나가 객체 후보 한 건, 관계 하나가 관계 후보 한 건이다. 그래서 AI 후보에는 PROPERTY·OBJECT_MAPPING·PROPERTY_MAPPING이 따로 오지 않고, 연결 규칙이 있는 관계의 LINK_TYPE도 따로 오지 않는다.

| 종류 | payload에 함께 오는 것 | 반영 |
| --- | --- | --- |
| OBJECT_TYPE | `objectMapping` {sourceId, resourceKey, keyColumns}, `properties[]` {code, name, dataType, isIdentifier, isRequired, unit, sensitivity, description, sourceColumn, transformSteps} | 한 번에 `object_type`, `property_definition`(속성마다), `object_source_mapping`, `property_source_mapping`(sourceColumn이 있는 속성마다)을 만든다. 순서는 위 선행 순서와 같다 |
| LINK_RULE | `linkType` {code, name, sourceTypeCode, targetTypeCode, cardinality, description} | `linkTypeCode`의 관계 유형이 없으면 `linkType`으로 먼저 `link_type`을 만들고 규칙을 반영한다. 있으면 `linkType`은 쓰지 않는다 |

- 묶음 안 하나라도 실패하면 묶음 전체가 실패한다(같은 트랜잭션).
- `objectMapping`·`properties`·`linkType`이 없는 OBJECT_TYPE·LINK_RULE은 지금까지처럼 정의 하나만 반영한다. 관리자가 직접 추가한 연결 규칙(MANUAL)도 그대로다.
- 규칙이 없는 관계 유형(문서 연결용 등)과 문서 연결은 지금처럼 LINK_TYPE·DOCUMENT_LINK 한 건씩 온다.

## 1단계 반영기 (정의·매핑 5종)

`ontology/service/apply` 패키지. 실제 저장과 검증은 관리자 직접 등록과 같은 서비스(API 022·027·031·035·038)가 하고, 반영기는 후보의 code를 ID로 바꿔 그 서비스를 부른다. 출처만 다르다.

| 종류 | payload | 만드는 것 |
| --- | --- | --- |
| OBJECT_TYPE | `code`, `name`, `description`, `synonyms`, `accessGroup`. 묶음이면 `properties[]`, `objectMapping` | 객체 유형. 묶음이면 속성 → 객체 매핑 → 속성 매핑(`properties[].sourceColumn`이 있는 것만) 순서 |
| PROPERTY | `objectTypeCode`, `code`, `name`, `dataType`, `isRequired`(생략 = false), `isIdentifier`, `unit`, `sensitivity`, `temporalRole`, `constraints`, `description` | 속성 |
| LINK_TYPE | `code`, `name`, `sourceTypeCode`, `targetTypeCode`, `cardinality`, `description` | 관계 유형. 설명이 비어 있으면 이름을 쓴다(DB 필수 칸) |
| OBJECT_MAPPING | `objectTypeCode`, `sourceId`, `resourceKey`, `keyColumns` | 켜진 객체 매핑. 그 표를 다시 읽기 대상으로 표시한다(`loaded_lsn = NULL`, 감시 작업이 첫 적재를 넣는다) |
| PROPERTY_MAPPING | `objectTypeCode`, `propertyCode`, `sourceColumn` {`sourceId`, `resourceKey`, `column`}, `transformSteps` | 속성 매핑. 부모는 그 표에서 이 유형을 만드는 켜진 객체 매핑 |

- 출처: AI 후보는 `origin = AI_PROPOSAL`, 관리자가 등록한 후보는 `MANUAL`이고 둘 다 `proposal_item_id`를 남긴다(속성 매핑 표에는 출처 칸이 없다).
- 원천은 `sourceId`(data_source.code)와 `resourceKey`로 가리킨다. 원천·자원·컬럼은 후보가 만들지 않으므로 없으면 `APPLY_REFERENCE_MISSING`(`requiredKind` DATA_SOURCE·SOURCE_RESOURCE·SOURCE_COLUMN)이다.
- 등록 서비스가 낸 오류는 반영 오류로 바꾼다: 중복(409) → `APPLY_TARGET_EXISTS`, 없음(404) → `APPLY_REFERENCE_MISSING`, 그 밖 입력 오류 → `APPLY_PAYLOAD_INVALID`. `field`는 `payload.`로 시작한다.
- 결과 `changes`에는 만든 행마다 INSERT 한 줄, `reloadResourceIds`에는 다시 읽기를 요청한 원천 자원이 들어간다.

## 2단계 반영기 (LINK_RULE, 186·189)

`LinkRuleApplier`. 순서는 아래와 같고 모두 승인 트랜잭션 하나다.

1. **관계 유형:** `linkTypeId`(관리자 후보) 또는 `linkTypeCode`로 찾는다. 코드로 찾을 수 없고 AI 묶음 `linkType`이 있으면 1단계와 같은 방식으로 먼저 만든다(`linkType.code`는 `linkTypeCode`와 같아야 한다). 없으면 `APPLY_REFERENCE_MISSING`(LINK_TYPE).
2. **개정본(186):** 같은 규칙 코드의 ACTIVE 개정본을 RETIRED로 바꾸고(이전 개정본은 지우지 않고 보존) 다음 번호의 개정본을 만든다. 같은 코드는 같은 관계 유형이어야 한다(다르면 `APPLY_PAYLOAD_INVALID`, `payload.linkTypeCode`). 비교 항목은 같은 트랜잭션에서 넣는다(DB 트리거가 개정본의 `created_at = now()`로 확인하므로 `created_at`은 DB 기본값이 채운다).
3. **연결 재계산:** `LinkRecomputeService.recomputeRules`로 그 관계 유형의 연결을 다시 계산한다. 옛 개정본이 근거인 연결도 새 개정본 기준으로 바뀐다(U6).
   같은 유형끼리 연결할 때는 자기 자신을 잇는 쌍을 제외한 뒤 관계 수와 보류 사유를 계산한다. [후보 시험과 SYNC](check-run.md)에도 같은 기준을 적용한다.
4. **보류 건(2026-11-09 결정 (a)):** 새 규칙으로 연결되지 않은 출발 객체를 `held_item`(HELD)으로 만든다. 이유는 `비교값이 같은 도착 객체가 없음`·`비교값이 같은 도착 객체가 둘 이상`·`같은 도착을 가리키는 출발 객체가 둘 이상`·`조건 N 변환 실패`·`비교할 값이 단일 값이 아님`이다.
   - 빼는 것: 출발 조건(sourceFilter)에 맞지 않는 객체, 비교값이 빈 객체(이을 값이 없음, SYNC가 다룸), 그 관계 유형의 활성 연결(규칙·수동·문서)이 이미 있는 객체, 같은 관계 유형·출발 객체에 열린 보류 건이 이미 있는 객체
   - issue는 만들지 않는다(결정 문서 8장). 출발 객체가 아직 적재 전이면 보류 건은 0건이고, 적재 뒤 첫 SYNC가 다룬다
   - `held_item`은 검토 도메인 표라서 반영기는 `HeldItemRecorder` 인터페이스로만 쓰고 구현(`HeldItemRecordService`)은 검토 도메인에 있다

| payload | 내용 |
| --- | --- |
| `code` | 규칙 코드 |
| `linkTypeId` 또는 `linkTypeCode` | 관계 유형. AI 묶음은 `linkType` {code, name, sourceTypeCode, targetTypeCode, cardinality, description}을 함께 보낸다 |
| `conditions[]` | {`sourceProperty`, `targetProperty`, `operator`(EQ), `sourceTransforms`, `targetTransforms`}. 속성은 `유형코드.속성코드` 또는 `속성코드`, 유형코드는 그 쪽(출발·도착) 유형과 같아야 한다. 변환은 속성 매핑과 같은 기준으로 검사한다 |
| `sourceFilter`, `targetFilter` | `{}` 또는 `{property, equals}`(연결 재계산이 읽는 형식). 속성은 그 쪽 유형의 속성이어야 한다 |
| `description` | 설명. `pattern`은 쓰지 않는다 |

   - 이 규칙으로는 이을 수 있었지만 같은 관계 유형의 다른 규칙과 결과가 달라(관계 수 제약) 실제로는 안 이어진 출발도 보류 건으로 남긴다(이유: `이 규칙으로는 이어지지만 같은 관계 유형의 다른 규칙·연결과 맞지 않아 잇지 않음`). 새 규칙 승인으로 기존 연결이 조용히 사라지지 않게 하기 위해서다.
5. **이미 이어진 보류 건 닫기(2026-11-09 결정 (a)):** 그 관계 유형의 열린 보류 건 중 재계산 뒤 출발 객체에 그 관계 유형의 규칙 연결이 있는 것은 `APPROVED`로 닫는다. 근거가 이 개정본이 아니어도 닫는다(같은 쌍을 원하는 다른 규칙 코드가 근거를 유지할 수 있다). `resolved_link_id`는 이번 재계산에서 생기거나 바뀐 연결을 먼저, 없으면 그 출발의 기존 규칙 연결 중 가장 작은 ID다. 기존 연결로 닫으면 `resolution_note`는 `규칙 {코드} 개정본 {N} 승인 때 이미 규칙 연결이 있어 닫음`이다(수집이 이미 이었지만 자동 해소가 아직 없어 열려 있던 건). `resolved_by`는 승인자, `resolution_note`는 `규칙 {코드} 개정본 {N} 승인으로 연결됨`이다. 열어 두면 관리자가 238에서 승인할 때 같은 쌍의 수동 연결을 또 만들려다 충돌한다. 자동 해소(`AUTO_RESOLVED`)는 수집 실행이 있어야 해서 승인 경로에서는 쓰지 않는다. 238에서 `APPROVED`는 "관리자 승인으로 연결됨"이라는 넓은 뜻이 된다.

결과: `targetTable = link_mapping`, `heldItemIds`(새로 만든 보류 건), `summary` {ruleCode, revision, retiredRevisionId, linksActivated, linksUpdated, linksInactivated, heldCount, heldClosedCount, closedHeldItemIds}, `changes`(관계 유형을 만들었으면 그 INSERT, 이전 개정본 UPDATE, 새 개정본 INSERT, 닫은 보류 건마다 HELD→APPROVED UPDATE). 연결 행마다의 변경은 건수로만 남긴다.

알려진 한계(후속에서 정함)

- 열린 보류 건 중복은 관계 유형 잠금 안의 애플리케이션 검사로만 막는다. DB 부분 고유 인덱스(`link_type_id, source_object_id WHERE status = 'HELD'`)는 스키마 변경이라 두지 않았다.
- 도착 표가 적재 중이거나 도착 연결 컬럼에 열린 수집 실패 기록이 있어도 보류 건을 만든다. SYNC 판정의 보류 기준(설계 검토 R5)을 승인 시점에도 둘지는 정해야 한다.
- 개정본을 RETIRED로 바꿀 때 `link_check_pending`의 그 개정본 대기 건을 닫는 일(V12 주석의 RULE_RETIRED)은 검사 쪽이 구현될 때 함께 한다.
- 이전 개정본이 남긴 보류 건 중 새 개정본에서 출발 조건에 빠졌거나 비교값이 비게 된 출발은 연결되지도 새로 보류되지도 않아 HELD로 남는다. 관리자가 238에서 종결(DISMISSED)한다.
- 보류 건은 (관계 유형, 출발 객체) 단위다. `*_TO_MANY`에서 규칙 R1이 못 이은 출발도 다른 규칙 R2로 연결이 하나 생기면 닫힌다(만들 때도 활성 연결이 하나라도 있으면 빼므로 같은 기준이다).
- 관계 유형의 양 끝 유형이 같은 때 바뀌는 경우는 잠그지 않는다(관계 유형이 쓰이고 있으면 032가 끝 유형 변경을 막는다).

## 테스트에서 반영기를 바꿔치기할 때

`ProposalApplyService`는 후보 종류마다 반영기가 하나여야 하고, 겹치면 컨텍스트가 뜨지 않는다. 그래서 통합 테스트(`@SpringBootTest`)에서 가짜 반영기를 **반영기 빈으로 더하지 않는다**(실제 반영기와 겹친다). 가짜 반영기는 `ProposalApplier`를 구현하지 않는 객체로 두고, 그것만 쓰는 서비스를 `@Primary`로 둔다. 예: `ProposalDecisionServiceTests`, `ProposalApplyResultQueryApiTests`.

```java
@Bean
@Primary
ProposalApplyService probeApplyService(RecordingApplier probe) {
    return new ProposalApplyService(List.of(new ProposalApplier() { ... probe에 위임 ... }));
}
```

## 실패

반영하지 못하면 `BusinessException`을 던진다.

| 코드 | HTTP | 경우 | details |
| --- | --- | --- | --- |
| `APPLY_PAYLOAD_INVALID` | 400 | payload 필드가 없거나 형식이 틀림 | `field` (예: `payload.keyColumns`) |
| `APPLY_REFERENCE_MISSING` | 422 | 가리키는 정의가 아직 없음(선행 후보 미승인) | `field`, `code`, `requiredKind` |
| `APPLY_TARGET_EXISTS` | 409 | 같은 code의 정의, 같은 자원·유형의 켜진 매핑이 이미 있음 | `field`, `code` |
| `APPLY_KIND_NOT_SUPPORTED` | 422 | 반영기가 아직 없는 종류 | `itemKind` |

- 예외가 나면 승인 트랜잭션은 롤백만 할 수 있다. 같은 트랜잭션에서 예외를 잡고 이어 가도 커밋할 때 `UnexpectedRollbackException`이 난다.
- 131은 업무 전체를 롤백한 뒤 실패 감사만 `AuditRecorder.recordSeparately`의 별도 트랜잭션으로 남긴다. 후보의 `apply_status='FAILED'`·`apply_error`는 별도 저장하지 않고 실행 전 상태를 유지한다. 실패 결과는 기존 실행 이력으로 조회한다([133 조회 계약](review-apply-result-query.md)).

## 확인 필요

- `property_source_mapping`에는 `proposal_item_id`·`origin` 칸이 없다. 어느 후보로 만든 속성 매핑인지는 감사 기록으로만 찾는다.
- 속성 매핑 후보의 `sourceColumn.table`은 스키마 없는 표 이름이라 `source_resource.resource_key`(`erp.attendance`)와 맞지 않는다([원천 매핑](source-mapping.md) 승인 반영 절). 11-05부터 AI 후보의 `sourceColumn`에 `resourceKey`(`erp.attendance` 형식)가 함께 온다(S15P31C106-289).
