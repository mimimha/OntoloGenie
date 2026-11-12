-- Extends smoke-fixture.sql only inside a newly created synthetic evaluation DB.
begin;
set local search_path=platform;
do $guard$
begin
  if current_database() <> 'axcore_eval' or current_user <> 'axcore_eval_owner'
      or (select count(*) from object_instance) <> 3
      or not exists(select 1 from object_type where code='eval-equipment') then
    raise exception 'Diverse fixture requires the untouched synthetic smoke fixture';
  end if;
end $guard$;

insert into object_type(code,name,access_group) values
  ('eval-fixed-asset','고정자산','EVAL_FINANCE'),
  ('eval-maintenance','정비 지시','PRODUCTION'),
  ('eval-work-order','작업지시','PRODUCTION'),
  ('eval-hidden','제한 설비','EVAL_HIDDEN');

insert into property_definition(object_type_id,code,name,data_type,is_identifier,sensitivity)
select t.id,v.code,v.name,v.kind,v.identifier,v.sensitivity
from (values
  ('eval-equipment','status','설비 상태','STRING',false,'NORMAL'),
  ('eval-downtime','reason_code','사유 코드','STRING',false,'NORMAL'),
  ('eval-fixed-asset','asset_no','자산 번호','STRING',true,'NORMAL'),
  ('eval-fixed-asset','book_value','장부가(원)','DECIMAL',false,'RESTRICTED'),
  ('eval-fixed-asset','acquisition_cost','취득가(원)','DECIMAL',false,'NORMAL'),
  ('eval-fixed-asset','internal_note','내부 메모','STRING',false,'RESTRICTED'),
  ('eval-maintenance','repair_no','정비 지시 번호','STRING',true,'NORMAL'),
  ('eval-maintenance','status','정비 상태','STRING',false,'NORMAL'),
  ('eval-work-order','wo_no','작업지시 번호','STRING',true,'NORMAL'),
  ('eval-work-order','status','진행 상태','STRING',false,'NORMAL'),
  ('eval-work-order','planned_quantity','계획 수량','INTEGER',false,'NORMAL'),
  ('eval-work-order','produced_quantity','생산 수량','INTEGER',false,'NORMAL'),
  ('eval-work-order','defect_quantity','불량 수량','INTEGER',false,'NORMAL'),
  ('eval-hidden','code','제한 설비 코드','STRING',true,'NORMAL'),
  ('eval-hidden','secret','제한 정보','STRING',false,'RESTRICTED')
) as v(type,code,name,kind,identifier,sensitivity)
join object_type t on t.code=v.type;

update object_instance set properties=properties || '{"status":"가동"}'::jsonb
where display_name='P103';
update object_instance set properties=properties || '{"reason_code":"BRK"}'::jsonb
where display_name='DT-0409';

insert into object_instance(object_type_id,display_name,properties)
select t.id,v.name,v.props::jsonb
from (values
  ('eval-equipment','P104','{"code":"P104","status":"정지"}'),
  ('eval-equipment','P105','{"code":"P105","status":"대기"}'),
  ('eval-equipment','P106','{"code":"P106","status":"정지"}'),
  ('eval-equipment','공용 프레스','{"code":"AMB-A","status":"가동"}'),
  ('eval-equipment','공용 프레스','{"code":"AMB-B","status":"정지"}'),
  ('eval-downtime','DT-0501','{"downtime_id":"DT-0501","reason":"코일 대기","started_at":"2026-09-12T10:00:00","ended_at":"2026-09-12T10:45:00","minutes":45}'),
  ('eval-downtime','DT-0601','{"downtime_id":"DT-0601","reason":"클러치 소음","started_at":"2026-09-20T11:00:00","ended_at":"2026-09-20T11:20:00","minutes":20}'),
  ('eval-downtime','DT-0701','{"downtime_id":"DT-0701","reason":"슬라이드 유압 누유","started_at":"2026-11-11T10:00:00","ended_at":null,"minutes":null}'),
  ('eval-fixed-asset','FA-0001','{"asset_no":"FA-0001","book_value":84000000,"acquisition_cost":540000000,"internal_note":"EVAL-SECRET-9472"}'),
  ('eval-fixed-asset','FA-0002','{"asset_no":"FA-0002","book_value":128000000,"acquisition_cost":360000000}'),
  ('eval-fixed-asset','FA-0003','{"asset_no":"FA-0003","book_value":268000000,"acquisition_cost":790000000}'),
  ('eval-maintenance','RP-26-004','{"repair_no":"RP-26-004","status":"완료"}'),
  ('eval-maintenance','RP-26-005','{"repair_no":"RP-26-005","status":"완료"}'),
  ('eval-maintenance','RP-26-012','{"repair_no":"RP-26-012","status":"진행"}'),
  ('eval-maintenance','RP-26-041','{"repair_no":"RP-26-041","status":"예정"}'),
  ('eval-work-order','JB2610-0138','{"wo_no":"JB2610-0138","status":"진행 중","planned_quantity":6000,"produced_quantity":3600,"defect_quantity":52}'),
  ('eval-hidden','HIDDEN-01','{"code":"HIDDEN-01","secret":"EVAL-HIDDEN-5831"}')
) as v(type,name,props)
join object_type t on t.code=v.type
order by v.type,v.name,v.props;

insert into link_type(code,name,source_type_id,target_type_id,cardinality,description)
select v.code,v.name,s.id,d.id,'MANY_TO_ONE',v.name
from (values
  ('eval-maintenance-equipment','정비 대상 설비','eval-maintenance','eval-equipment'),
  ('eval-workorder-equipment','작업 설비','eval-work-order','eval-equipment'),
  ('eval-equipment-asset','설비 고정자산','eval-equipment','eval-fixed-asset')
) as v(code,name,source_type,target_type)
join object_type s on s.code=v.source_type
join object_type d on d.code=v.target_type;

insert into object_link(link_type_id,source_object_id,target_object_id,origin,confirmed_by)
select l.id,s.id,d.id,'MANUAL',a.id
from (values
  ('eval-downtime-equipment','DT-0501','P103'),
  ('eval-downtime-equipment','DT-0601','P104'),
  ('eval-downtime-equipment','DT-0701','P106'),
  ('eval-maintenance-equipment','RP-26-004','P103'),
  ('eval-maintenance-equipment','RP-26-005','P103'),
  ('eval-maintenance-equipment','RP-26-012','P103'),
  ('eval-maintenance-equipment','RP-26-041','P103'),
  ('eval-workorder-equipment','JB2610-0138','P103'),
  ('eval-equipment-asset','P103','FA-0001'),
  ('eval-equipment-asset','P104','FA-0002')
) as v(type,source_name,target_name)
join link_type l on l.code=v.type
join object_instance s on s.display_name=v.source_name
join object_instance d on d.display_name=v.target_name
cross join account a
where a.login_id='evaluation-fixture';

insert into role_data_scope(role_id,access_group,can_view_restricted)
select r.id,v.access_group,false from role r
cross join (values ('PRODUCTION'),('EVAL_FINANCE')) as v(access_group)
where r.code='VIEWER'
on conflict (role_id,access_group) do update set can_view_restricted=false;
commit;
