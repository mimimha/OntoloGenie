# 원천 토픽 실제 메시지 (Jira 198-2 시험 자료)

`debezium-tx-messages.jsonl`은 Debezium 3.6.3(서버와 같은 커넥터 설정 `infra/cdc/connectors/cdc-erp.json`, heartbeat만 2초)에서 원천 토픽 `ERP_MAIN.all`을 처음부터 읽은 메시지 37건이다. 줄 번호 - 1이 Kafka offset이다.

원천에서 실행한 순서:

```sql
create schema erp;
create table erp.equipment(id bigint primary key, name text);
create table erp.work_order(id bigint primary key, eq_id bigint, wo_no text);
create table erp.doc(id bigint primary key, n int, big text);
create table public.unpub(id int);                       -- publication 밖
create publication axcore_cdc for tables in schema erp;
-- (커넥터 등록)
begin; insert into erp.equipment values (3, 'EQ-3'); insert into erp.work_order values (7, 3, 'WO-7'), (8, 3, 'WO-8'); commit;  -- tx 756, 중간에 heartbeat가 낌
update erp.work_order set wo_no = 'WO-7b' where id = 7;                                                                          -- tx 757
begin; insert into erp.work_order values (9, 3, 'WO-9'); savepoint s; update erp.equipment set name = 'x' where id = 3;
  rollback to savepoint s; delete from erp.work_order where id = 8; commit;                                                      -- tx 758
update erp.equipment set id = 30 where id = 3;                                                                                   -- tx 761, PK 변경 = d + c
insert into erp.doc select 1, 0, string_agg(md5(i::text), '') from generate_series(1, 300) i;                                    -- tx 762
update erp.doc set n = 1 where id = 1;                                                                                           -- tx 763, TOAST 미제공 값
insert into public.unpub values (1);                                                                                             -- 메시지 없음
begin; select 1; commit;                                                                                                         -- 메시지 없음
truncate erp.doc;                                                                                                                -- tx 765, op=t
```
