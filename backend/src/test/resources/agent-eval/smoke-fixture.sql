-- Only an empty, newly restored local evaluation DB may receive this synthetic fixture.
set search_path=platform;
do $fixture$
declare
  equipment_type bigint;
  downtime_type bigint;
  equipment bigint;
  guide bigint;
  lubrication bigint;
  relation bigint;
  administrator bigint;
begin
  if current_database() <> 'axcore_eval' or current_user <> 'axcore_eval_owner'
       or exists(select 1 from object_instance) then
    raise exception 'Synthetic fixture requires a new empty local evaluation copy';
  end if;
  insert into account(login_id, display_name, password_hash, role_id, status)
    values ('evaluation-fixture', '평가용 가상 계정', '!EVALUATION_DISABLED!',
      (select id from role where code='ADMIN'), 'DISABLED') returning id into administrator;
  insert into object_type(code,name,access_group) values ('eval-equipment','설비','PRODUCTION')
    returning id into equipment_type;
  insert into object_type(code,name,access_group) values ('eval-downtime','비가동','PRODUCTION')
    returning id into downtime_type;
  insert into property_definition(object_type_id,code,name,data_type,is_identifier)
    values (equipment_type,'code','설비 코드','STRING',true),
      (downtime_type,'downtime_id','비가동 번호','STRING',true),
      (downtime_type,'reason','사유','STRING',false),
      (downtime_type,'started_at','시작 시각','DATETIME',false),
      (downtime_type,'ended_at','종료 시각','DATETIME',false),
      (downtime_type,'minutes','비가동 시간(분)','INTEGER',false);
  insert into object_instance(object_type_id,display_name,properties)
    values (equipment_type,'P103','{"code":"P103"}') returning id into equipment;
  insert into object_instance(object_type_id,display_name,properties)
    values (downtime_type,'DT-0409',
      '{"downtime_id":"DT-0409","reason":"가이드 마모","started_at":"2026-08-31T06:00:00","ended_at":"2026-08-31T09:00:00","minutes":180}')
    returning id into guide;
  insert into object_instance(object_type_id,display_name,properties)
    values (downtime_type,'DT-0410',
      '{"downtime_id":"DT-0410","reason":"윤활 계통 점검","started_at":"2026-08-31T12:10:00","ended_at":"2026-08-31T15:10:00","minutes":180}')
    returning id into lubrication;
  insert into link_type(code,name,source_type_id,target_type_id,cardinality,description)
    values ('eval-downtime-equipment','비가동 설비',downtime_type,equipment_type,'MANY_TO_ONE','가상 비가동 기록의 설비')
    returning id into relation;
  insert into object_link(link_type_id,source_object_id,target_object_id,origin,confirmed_by)
    values (relation,guide,equipment,'MANUAL',administrator),
      (relation,lubrication,equipment,'MANUAL',administrator);
end $fixture$;
