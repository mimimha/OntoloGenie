-- =====================================================================
-- V16: CDC 복구 이유에 APPLY_FAILED를 더한다 (S15P31C106-364)
--   같은 위치의 원천 트랜잭션 반영이 연속으로 실패하면(DB 제약 위반·버그처럼 다시 시도해도 같은 오류) 소비자가 끝없이 다시 시도하지 않고
--   그 원천을 복구로 돌린다. 다시 읽기로 지금 값을 맞추고, 풀린 뒤에는 그 트랜잭션을 건너뛴다(resume_after, V14).
--   기존 행은 바꾸지 않는다. 제약만 넓힌다.
-- =====================================================================

ALTER TABLE cdc_offset DROP CONSTRAINT ck_cdc_offset_recovery_reason;
ALTER TABLE cdc_offset
    ADD CONSTRAINT ck_cdc_offset_recovery_reason
        CHECK (recovery_reason IS NULL
               OR recovery_reason IN ('SLOT_LOST', 'OFFSET_EXPIRED', 'EPOCH_UNCERTAIN', 'TRANSACTION_TOO_LARGE', 'PROTOCOL_ERROR',
                                      'SOURCE_CHANGED', 'APPLY_FAILED'));

COMMENT ON COLUMN cdc_offset.recovery_reason IS '복구(그 원천의 CDC 테이블 전체 다시 읽기)가 필요한 이유. SLOT_LOST 복제 슬롯 유실·재생성(원천 교체 뒤 관리자가 커넥터를 새 DB에 맞췄다고 알린 경우 포함), OFFSET_EXPIRED 보관 기간 초과, EPOCH_UNCERTAIN txId epoch를 확신할 수 없음, TRANSACTION_TOO_LARGE 원천 트랜잭션이 상한 초과, PROTOCOL_ERROR 메시지 순서·모양 오류, APPLY_FAILED 같은 위치의 반영이 연속으로 실패(V16), SOURCE_CHANGED 원천 접속 정보(host·port·database)가 바뀜(API 012). SOURCE_CHANGED는 커넥터를 새 DB에 맞출 때까지 다시 읽지 않는다. 그 밖의 이유는 켜진 매핑이 있는 CDC 테이블이 모두 recovery_at 뒤에 시작한 전체 읽기로 적재되면 그 저장 트랜잭션에서 풀린다(199). NULL이면 복구 필요 없음';
