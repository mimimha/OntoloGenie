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
| `reloadResourceIds` | 매핑을 반영해 전체 다시 읽기(`loaded_lsn = NULL`)를 요청한 원천 자원. 객체는 수집 배치가 만든다 |
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

반영기가 없는 종류는 `APPLY_KIND_NOT_SUPPORTED`로 거절한다. 지금은 모든 종류가 이 상태이고, 단계마다 반영기를 더한다. 호출 방식은 바뀌지 않는다.

선행 후보가 먼저 반영돼야 한다: 객체 유형 → 속성·관계 유형 → 객체 매핑 → 속성 매핑 → 관계 규칙.

## 실패

반영하지 못하면 `BusinessException`을 던진다.

| 코드 | HTTP | 경우 | details |
| --- | --- | --- | --- |
| `APPLY_PAYLOAD_INVALID` | 400 | payload 필드가 없거나 형식이 틀림 | `field` (예: `payload.keyColumns`) |
| `APPLY_REFERENCE_MISSING` | 422 | 가리키는 정의가 아직 없음(선행 후보 미승인) | `field`, `code`, `requiredKind` |
| `APPLY_TARGET_EXISTS` | 409 | 같은 code의 정의, 같은 자원·유형의 켜진 매핑이 이미 있음 | `field`, `code` |
| `APPLY_KIND_NOT_SUPPORTED` | 422 | 반영기가 아직 없는 종류 | `itemKind` |

- 예외가 나면 승인 트랜잭션은 롤백만 할 수 있다. 같은 트랜잭션에서 예외를 잡고 이어 가도 커밋할 때 `UnexpectedRollbackException`이 난다.
- `apply_status='FAILED'`·`apply_error`와 실패 감사 기록은 롤백 뒤 별도 트랜잭션(`REQUIRES_NEW`, 감사는 `AuditRecorder.recordSeparately`)으로 남긴다. 이 처리는 131이 한다. `REQUIRES_NEW` 메서드는 다른 빈에 둬야 새 트랜잭션이 열린다.

## 확인 필요

- `property_source_mapping`에는 `proposal_item_id`·`origin` 칸이 없다. 어느 후보로 만든 속성 매핑인지는 감사 기록으로만 찾는다.
- 속성 매핑 후보의 `sourceColumn.table`은 스키마 없는 표 이름이라 `source_resource.resource_key`(`erp.attendance`)와 맞지 않는다([원천 매핑](source-mapping.md) 승인 반영 절).
