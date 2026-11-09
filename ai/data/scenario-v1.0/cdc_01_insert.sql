-- CDC 변경 재현 시나리오. 최초 적재(data-v1.0)와 CDC 커넥터 등록 뒤 원천 MES DB에 단계별로 적용한다(분석 입력 아님). 원천 DB 관리자 계정으로 실행한다. 플랫폼 계정(platform_ro·platform_ai·platform_cdc)은 쓰기 권한이 없다. 적용한 동안에는 수주 참조 연결률(134/136 → 135/137)과 OD-2610-020의 작업지시 수가 바뀌므로 S1~S5 시연 전에는 cdc_99_restore.sql로 되돌린다.
-- 1. 작업지시 추가 (cdc_changes.json 참고)
begin;
insert into mes.work_order (wo_no, product_code, die_drawing_code, status, priority, line, planned_qty, produced_qty, defect_qty, planned_start, planned_end, actual_start, actual_end, so_ref) values ('JB2610-0901', 'PNL-410', 'DWG-PNL-410', '대기', '보통', '2라인', '3000', '0', '0', '2026-11-02', '2026-11-03', null, null, '2610020');
insert into mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) values ('JB2610-0901', '10', 'B101', '대기', '3000');
insert into mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) values ('JB2610-0901', '20', 'P201', '대기', '3000');
insert into mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) values ('JB2610-0901', '30', 'P201', '대기', '3000');
insert into mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) values ('JB2610-0901', '40', 'W201', '대기', '3000');
insert into mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) values ('JB2610-0901', '50', 'I101', '대기', '3000');
commit;
