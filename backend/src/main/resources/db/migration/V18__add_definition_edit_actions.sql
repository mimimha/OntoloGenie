-- =====================================================================
-- V18: 온톨로지 정의 직접 편집의 권한 코드와 관리 Action 정의 (Jira S15P31C106-187, FN-ONT-07·FN-LOG-03)
--   정의 편집 API(022·024·025·027~029·031~033·035·036·038·039·101)는 지금까지 감사 기록을 남기지 않았다.
--   이 정의는 그 실행을 감사 기록(action_execution·entity_change)에 잇고, 044 정의 변경 이력에서 후보 승인(DECIDE_PROPOSAL)과
--   구분하는 데 쓴다. 관리 Action이라 각 API가 직접 실행하고 091로는 실행하지 않는다(V9 RECORD). 데이터만 넣고 표 구조는 바꾸지 않는다.
-- =====================================================================

INSERT INTO permission (code, name, description) VALUES
    ('ONTOLOGY_MANAGE', '온톨로지 정의 관리',
     '객체 유형·속성·관계 유형·객체 매핑·속성 매핑의 등록·수정·삭제 (API 022·024·025·027~029·031~033·035·036·038·039·101)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code = 'ONTOLOGY_MANAGE'
WHERE r.code = 'ADMIN';

INSERT INTO action_definition (
    code, name, description, target_kind, action_mode, action_kind, requires_confirmation, required_permission
) VALUES
    ('CREATE_OBJECT_TYPE', '객체 유형 등록',
     '관리자가 객체 유형을 직접 등록한다(API 022). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('UPDATE_OBJECT_TYPE', '객체 유형 수정',
     '관리자가 객체 유형의 이름·설명·동의어·접근 그룹·표시 속성을 바꾼다(API 024). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('DELETE_OBJECT_TYPE', '객체 유형 삭제',
     '관리자가 쓰이지 않는 객체 유형을 지운다(API 025). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('CREATE_PROPERTY', '속성 등록',
     '관리자가 객체 유형에 속성을 직접 등록한다(API 027). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('UPDATE_PROPERTY', '속성 수정',
     '관리자가 속성의 이름·자료형·제약·민감도 등을 바꾼다(API 028). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('DELETE_PROPERTY', '속성 삭제',
     '관리자가 쓰이지 않는 속성을 지운다(API 029). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('CREATE_LINK_TYPE', '관계 유형 등록',
     '관리자가 관계 유형을 직접 등록한다(API 031). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('UPDATE_LINK_TYPE', '관계 유형 수정',
     '관리자가 관계 유형의 이름·설명·관계 수 등을 바꾼다(API 032). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('DELETE_LINK_TYPE', '관계 유형 삭제',
     '관리자가 쓰이지 않는 관계 유형을 지운다(API 033). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('CREATE_OBJECT_MAPPING', '객체 매핑 등록',
     '관리자가 원천 표와 객체 유형의 매핑을 직접 등록한다(API 035). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('UPDATE_OBJECT_MAPPING', '객체 매핑 수정',
     '관리자가 객체 매핑의 원천 표·키·켜짐 여부를 바꾼다(API 036). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('CREATE_PROPERTY_MAPPING', '속성 매핑 등록',
     '관리자가 원천 컬럼과 속성의 매핑을 직접 등록한다(API 038). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('UPDATE_PROPERTY_MAPPING', '속성 매핑 수정',
     '관리자가 속성 매핑의 원천 컬럼·변환 단계를 바꾼다(API 039). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE'),
    ('DELETE_PROPERTY_MAPPING', '속성 매핑 삭제',
     '관리자가 속성 매핑을 지운다(API 101). 정의 API가 직접 실행하고 091로는 실행하지 않는다.',
     'SYSTEM', 'RECORD', 'WRITE', false, 'ONTOLOGY_MANAGE');
