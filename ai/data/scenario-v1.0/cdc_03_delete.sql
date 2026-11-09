-- CDC 변경 재현 시나리오. 최초 적재(data-v1.0)와 CDC 커넥터 등록 뒤 원천 MES DB에 단계별로 적용한다(분석 입력 아님). 원천 DB 관리자 계정으로 실행한다. 플랫폼 계정(platform_ro·platform_ai·platform_cdc)은 쓰기 권한이 없다. 적용한 동안에는 수주 참조 연결률(134/136 → 135/137)과 OD-2610-020의 작업지시 수가 바뀌므로 S1~S5 시연 전에는 cdc_99_restore.sql로 되돌린다.
-- 3. 마지막 공정 취소(행 삭제) (cdc_changes.json 참고)
begin;
delete from mes.work_order_operation where wo_no = 'JB2610-0901' and op_seq = '50';
commit;
