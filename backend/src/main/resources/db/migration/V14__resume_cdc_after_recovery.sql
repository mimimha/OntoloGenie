-- =====================================================================
-- V14 CDC 복구 해제와 이어 받기 (S15P31C106-199, 2026-11-10)
-- 결정: decisions/2026-11-10-199-cdc-recovery-design.md(작업 폴더), 2026-11-05-cdc-initial-load.md 11.1
--   1. cdc_offset.resume_after: 복구를 풀 때 "이 시각 뒤에 토픽에 쓰인 메시지부터 다시 읽는다"를 남긴다.
--      저장된 위치부터 다시 읽으면 크기 초과·메시지 오류를 낸 메시지를 또 만나 복구가 반복되고, 원천 교체 뒤에는 옛 DB 이벤트를 읽는다.
--      기준 시각은 그 원천을 다시 읽은 전체 읽기 중 가장 이른 시작 시각이다. 그 뒤에 커밋된 원천 트랜잭션은 그 뒤에 토픽에 쓰이고,
--      그 앞에 쓰인 메시지의 트랜잭션은 다시 읽은 스냅샷에 들어 있다.
--   2. 주석만 고침(11.1-5): sync_run의 MANUAL·RECOVERY 뜻, source_resource.loaded_lsn, cdc_offset 복구 칸.
-- 기존 행은 새 칸이 NULL이라 지금처럼 next_offset부터 읽는다.
-- =====================================================================

ALTER TABLE cdc_offset
    ADD COLUMN resume_after timestamptz,
    ADD CONSTRAINT ck_cdc_offset_resume CHECK (resume_after IS NULL OR recovery_reason IS NULL);

COMMENT ON COLUMN cdc_offset.resume_after IS '복구를 푼 뒤 이어 받을 기준 시각(V14, 199). 그 원천을 다시 읽은 전체 읽기 중 가장 이른 시작 시각이다. 소비자는 이 시각 뒤에 토픽에 쓰인 첫 메시지(Kafka offsetsForTimes)로 가서 처음 만나는 BEGIN부터 반영하고, 그 BEGIN을 next_offset으로 저장하며 이 칸을 비운다. NULL이면 next_offset부터 읽는다';

COMMENT ON COLUMN cdc_offset.recovery_reason IS '복구(그 원천의 CDC 테이블 전체 다시 읽기)가 필요한 이유. SLOT_LOST 복제 슬롯 유실·재생성(원천 교체 뒤 관리자가 커넥터를 새 DB에 맞췄다고 알린 경우 포함), OFFSET_EXPIRED 보관 기간 초과, EPOCH_UNCERTAIN txId epoch를 확신할 수 없음, TRANSACTION_TOO_LARGE 원천 트랜잭션이 상한 초과, PROTOCOL_ERROR 메시지 순서·모양 오류, SOURCE_CHANGED 원천 접속 정보(host·port·database)가 바뀜(API 012). SOURCE_CHANGED는 커넥터를 새 DB에 맞출 때까지 다시 읽지 않는다. 그 밖의 이유는 켜진 매핑이 있는 CDC 테이블이 모두 recovery_at 뒤에 시작한 전체 읽기로 적재되면 그 저장 트랜잭션에서 풀린다(199). NULL이면 복구 필요 없음';

COMMENT ON COLUMN cdc_offset.recovery_at IS '복구가 필요해진 시각. 이 시각보다 먼저 시작한 전체 읽기는 복구 원인보다 옛 스냅샷이라 다시 읽은 것으로 치지 않는다(loaded_lsn을 채우지 않음)';

COMMENT ON COLUMN source_resource.loaded_lsn IS 'CDC 테이블을 마지막으로 전체 읽기한 시점의 원천 LSN(참고·표시용, 판단에 쓰지 않는다). NULL이면 다시 전체 읽기가 필요하다: 첫 적재 전, 매핑 변경·TRUNCATE·TOAST 값 유지 불가 뒤, 복구. 배치 감시 작업이 켜진 매핑이 있는 NULL 테이블을 RECOVERY로 다시 읽는다. 원천 교체(SOURCE_CHANGED)는 커넥터를 맞추기 전에 읽으면 안 되므로 비우지 않고, 관리자가 맞췄다고 알릴 때 비운다. 원천이 복구 중이면 recovery_at보다 먼저 시작한 전체 읽기는 이 값을 채우지 않는다. CDC 이벤트는 이 값과 무관하게 항상 반영한다(V11)';

COMMENT ON TABLE sync_run IS '원천 수집 한 번(FN-BAT-02~04). CDC = 소비자가 한 트랜잭션으로 반영한 이벤트 묶음 하나(변경이 없으면 만들지 않음). MANUAL = 관리자가 요청한 전체 다시 읽기(API 073, CDC 테이블은 FULL만), SCHEDULED = FULL_COMPARE 원천의 주기 수집, ON_DEMAND = Agent 질문에 필요한 FULL_COMPARE 테이블만, RECOVERY = 배치 감시 작업이 요청자 없이 넣는 CDC 테이블 전체 다시 읽기(첫 적재, 매핑 변경·TRUNCATE 뒤, 복구). 넣은 이유는 접수 감사 기록에 이유 코드(FIRST_LOAD·RECOVERY·RELOAD_REQUESTED)로 남긴다. CDC 외에는 작업 표 방식이다: 백엔드·감시 작업이 QUEUED로 넣고 배치가 가져가 RUNNING으로 바꾼다. 테이블별 구간·건수는 sync_run_resource';
