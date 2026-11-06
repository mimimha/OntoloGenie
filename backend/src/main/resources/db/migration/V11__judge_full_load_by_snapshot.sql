-- =====================================================================
-- V11 전체 읽기와 CDC 이벤트의 경계를 스냅샷 가시성으로 판단한다 (S15P31C106-197, 2026-11-05·06 결정)
-- V5는 "전체 읽기 직전 LSN(source_resource.loaded_lsn)보다 앞선 이벤트는 버린다"였다. 그런데 커밋 전 변경은 SELECT에 안 보이고
-- 그 변경 LSN은 읽기 직전 LSN보다 앞서므로, 이 규칙은 변경을 잃는다(LSN 100에 쓰고 300에 커밋, SELECT는 200).
-- 그래서 전체 읽기는 REPEATABLE READ 한 트랜잭션에서 pg_current_snapshot()과 SELECT를 같이 하고, 객체마다 그 스냅샷을 남긴다.
--   CDC 소비자 : 이벤트 트랜잭션 번호가 객체의 loaded_snapshot에서 보이면 이미 적재값에 들어 있으므로 건너뛴다. 재전송은 source_lsn으로 거른다
--   전체 읽기  : 객체의 source_txid가 자기 스냅샷에서 보일 때만 덮어쓴다(아니면 객체가 더 새롭다). 옛 세대 적재는 새 세대를 덮지 않는다
--   CDC 이벤트는 전체 읽기 전·도중에도 버리지 않는다. loaded_lsn은 "다시 읽기 필요" 표시이자 참고값으로만 남는다
-- 객체가 아직 없을 때 온 원천 삭제는 object_instance가 아니라 새 표 source_delete_mark에 남긴다.
-- 복제 슬롯 유실·Kafka 보관 초과 뒤 요청자 없이 시작하는 전체 다시 읽기는 trigger_kind=RECOVERY로 남긴다.
-- 기존 행은 새 컬럼이 모두 NULL이라 다음 전체 읽기 전까지는 판단 없이 이벤트를 반영한다(지금까지 CDC 소비자는 없었다).
-- =====================================================================

-- 1. 원천 테이블: 끝난 전체 읽기의 세대
ALTER TABLE source_resource
    ADD COLUMN full_load_done_gen bigint,
    ADD CONSTRAINT ck_source_resource_load_gen CHECK (full_load_done_gen IS NULL OR sync_strategy = 'CDC');

COMMENT ON COLUMN source_resource.loaded_lsn IS 'CDC 테이블을 마지막으로 전체 읽기한 시점의 원천 LSN(참고·표시용, 판단에 쓰지 않는다). NULL이면 다시 전체 읽기가 필요하다(첫 적재 전·매핑 변경 후·복구). 매핑을 바꾸면 NULL로 되돌려 다시 읽기를 요청한다. CDC 이벤트는 이 값과 무관하게 항상 반영한다(V11)';
COMMENT ON COLUMN source_resource.full_load_done_gen IS '이 CDC 테이블에서 끝난 전체 읽기 중 가장 새 세대(sync_run.id). 이보다 옛 세대 적재는 객체를 쓰지 않는다. 옛 적재에는 새 적재보다 새로운 정보가 없고, 새 적재가 지운 삭제 흔적을 되살리지 않기 위해서다(V11)';

-- 2. 객체: 마지막 반영 트랜잭션, 마지막 적재 스냅샷과 세대
ALTER TABLE object_instance
    ADD COLUMN source_txid     xid8,
    ADD COLUMN loaded_snapshot pg_snapshot,
    ADD COLUMN loaded_gen      bigint,
    ADD CONSTRAINT ck_object_instance_load      CHECK (source_mapping_id IS NOT NULL OR (source_txid IS NULL AND loaded_snapshot IS NULL AND loaded_gen IS NULL)),
    ADD CONSTRAINT ck_object_instance_load_pair CHECK ((loaded_snapshot IS NULL) = (loaded_gen IS NULL));

COMMENT ON COLUMN object_instance.last_synced_at IS '내용이 마지막으로 바뀐(추가·수정·삭제 표시) 시각. 값이 안 바뀐 행은 수집해도 이 값과 last_sync_run_id를 바꾸지 않는다(전체 읽기는 loaded_snapshot·loaded_gen만 갱신). 테이블을 마지막으로 읽은 시각은 sync_run_resource. 업무 값의 기준일은 temporal_role=AS_OF 속성으로 따로 표시한다';
COMMENT ON COLUMN object_instance.source_lsn IS 'CDC 테이블에서 이 객체에 마지막으로 반영한 이벤트의 LSN. 같은 이벤트가 다시 오면(재전송) 이 값 이하는 반영하지 않는다. 전체 읽기는 이 값을 바꾸지 않는다. FULL_COMPARE 테이블과 플랫폼 객체는 NULL';
COMMENT ON COLUMN object_instance.source_txid IS '이 객체에 마지막으로 반영한 원천 트랜잭션 번호(xid8). Debezium txId(32비트)는 소비자가 갱신하는 최신 기준값으로 epoch를 복원해 넣는다. 전체 읽기는 이 번호가 자기 스냅샷에서 보일 때만 객체를 덮어쓴다';
COMMENT ON COLUMN object_instance.loaded_snapshot IS '이 객체를 마지막으로 적재한 전체 읽기의 스냅샷(pg_current_snapshot). CDC 이벤트의 번호가 이 스냅샷에서 보이면 이미 적재값에 들어 있으므로 건너뛴다. 값이 같아도 전체 읽기마다 갱신한다';
COMMENT ON COLUMN object_instance.loaded_gen IS '이 객체를 마지막으로 적재한 전체 읽기의 세대(sync_run.id). 옛 세대 적재가 새 세대 적재를 덮지 않게 한다';

-- 3. 원천 삭제 흔적: 객체가 없을 때 온 삭제
--   객체 표에 삭제 표시 행을 두지 않는다. 매핑·객체 유형 삭제 막기, 키 변경 막기 트리거, 탐색 화면이 그 행을 진짜 객체로 세고,
--   삭제 이벤트에는 PK만 있어 어느 매핑의 객체인지 가릴 수 없기 때문이다
CREATE TABLE source_delete_mark (
    source_resource_id bigint      NOT NULL REFERENCES source_resource (id) ON DELETE CASCADE,
    source_key         jsonb       NOT NULL,              -- 원천 PK(컬럼 → 값)
    source_txid        xid8        NOT NULL,              -- 지운 원천 트랜잭션 번호(epoch 복원)
    source_lsn         bigint      NOT NULL,              -- 재전송 거르기
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_resource_id, source_key),
    CONSTRAINT ck_source_delete_mark_key CHECK (jsonb_typeof(source_key) = 'object'),
    CONSTRAINT ck_source_delete_mark_lsn CHECK (source_lsn >= 0)
);

COMMENT ON TABLE source_delete_mark IS '플랫폼에 객체가 없는 원천 행의 삭제 흔적. CDC 소비자가 d 이벤트에 맞는 객체를 하나도 찾지 못하면 남긴다. 전체 읽기는 객체를 새로 만들기 전에 확인해, 흔적의 번호가 자기 스냅샷에서 안 보이면(읽은 뒤에 지워진 행) 만들지 않는다. 전체 읽기가 끝나면 그 스냅샷에서 보이는 흔적은 지운다';

-- 4. 수집 실행: 요청자 없는 복구 읽기
ALTER TABLE sync_run
    DROP CONSTRAINT ck_sync_run_trigger,
    DROP CONSTRAINT ck_sync_run_requester;
ALTER TABLE sync_run
    ADD CONSTRAINT ck_sync_run_trigger   CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'ON_DEMAND', 'CDC', 'RECOVERY')),
    ADD CONSTRAINT ck_sync_run_requester CHECK (trigger_kind IN ('SCHEDULED', 'CDC', 'RECOVERY') OR requested_by IS NOT NULL);

COMMENT ON TABLE sync_run IS '원천 수집 한 번(FN-BAT-02~04). CDC = 소비자가 한 트랜잭션으로 반영한 이벤트 묶음 하나(변경이 없으면 만들지 않음). MANUAL = 관리자 요청·매핑 변경 뒤 전체 다시 읽기(CDC 테이블은 FULL만), SCHEDULED = FULL_COMPARE 원천의 주기 수집, ON_DEMAND = Agent 질문에 필요한 FULL_COMPARE 테이블만, RECOVERY = 복제 슬롯 유실·Kafka 보관 초과 뒤 요청자 없이 시작하는 CDC 테이블 전체 다시 읽기. CDC 외에는 작업 표 방식이다: 백엔드가 QUEUED로 넣고 배치가 가져가 RUNNING으로 바꾼다. 테이블별 구간·건수는 sync_run_resource';
COMMENT ON COLUMN sync_run_resource.to_lsn IS 'INCREMENTAL: 이 묶음에서 반영한 마지막 이벤트의 LSN. FULL(CDC 테이블): 읽기 시점의 원천 LSN(source_resource.loaded_lsn이 되는 값, 참고용). 전체 읽기와 이벤트의 경계는 객체의 loaded_snapshot으로 판단한다';
COMMENT ON TABLE object_source_mapping IS '원천 자원의 행에서 어떤 객체 유형을 만들지와 원천 키. 관리자가 직접 편집한다(2026-10-27). 객체가 만들어진 뒤에는 키 컬럼을 바꿀 수 없다(트리거). 컬럼·변환·행 조건을 바꾸면 그 테이블을 전체 다시 읽어 새 매핑을 적용한다(CDC 테이블은 loaded_lsn을 비워 다시 읽기를 요청하고, 그동안에도 CDC 이벤트는 계속 반영한다)';
