-- 197(JDBC)과 198(Debezium)이 같은 원천 행에서 같은 원천 키·속성 값을 만드는지 비교하는 시험 데이터(Jira 198-1).
-- debezium-typed-events.jsonl은 이 SQL을 Debezium 3.6.3(서버와 같은 커넥터 설정)에 흘려 받은 실제 메시지다. 둘을 같이 고친다.
-- timestamptz 값은 세션 시간대에 영향받지 않게 오프셋을 붙여 적는다.
create schema erp;
create table erp.typed (
    id     bigint primary key,
    code   varchar(20),
    qty    numeric(12, 2),
    ratio  numeric,
    small  smallint,
    cnt    integer,
    price  double precision,
    flag   boolean,
    day    date,
    tm     time,
    tm3    time(3),
    ts     timestamp,
    ts0    timestamp(0),
    tstz   timestamptz,
    doc    jsonb,
    uid    uuid,
    note   text
);
create table erp.line (
    order_no text,
    line_no  integer,
    amount   numeric(10, 3),
    primary key (order_no, line_no)
);
create table erp.by_day (day date primary key, val integer);
create table erp.by_instant (at timestamptz primary key, val integer);
create table erp.by_text (code text primary key, val integer);

insert into erp.typed values
    (1, 'WO-0001', 10.50, 3.14159, 7, 42, 1.5, true, '2026-11-06', '09:30:00', '09:30:00.123',
     '2026-11-06 09:30:00.123456', '2026-11-06 09:30:00', '2026-11-06 09:30:00.5+09:00',
     '{"b": 1, "a": [1, 2]}', '0b8c4e7a-3a51-4f2b-9d6c-1f0e2a3b4c5d', '설비 점검'),
    (2, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
    (3, '  -WO 3 ', -0.01, 'NaN', -32768, -2147483648, -0.25, false, '1969-12-31', '00:00:00', '23:59:59.999',
     '1969-12-31 23:59:59.999999', '1900-01-01 00:00:00', '1969-12-31 23:59:59-01:00',
     '[]', 'ffffffff-ffff-ffff-ffff-ffffffffffff', '😀 이모지');
insert into erp.line values ('SO-0001', 3, 12.500), ('SO-0001', 10, 0.000);
insert into erp.by_day values ('2026-11-06', 1), ('1969-12-31', 2);
insert into erp.by_instant values ('2026-11-06 09:00:00+09:00', 1), ('2026-11-06 00:00:00.000001+00:00', 2);
insert into erp.by_text values ('WO-0001', 1), ('설비 A', 2);
