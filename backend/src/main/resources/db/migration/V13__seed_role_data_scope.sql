-- =====================================================================
-- V13: 역할별 객체 조회 범위 초기 데이터 (Jira S15P31C106-205, 결정 D1 초안 2026-11-09)
--   접근 그룹은 4개다: PRODUCTION(생산·설비), SALES_SUPPLY(영업·구매·재고), FINANCE(재무), HR(인사).
--   ADMIN은 코드가 모두 허용하므로 행이 없다. FINANCE·HR은 일반 역할에 열지 않아 ADMIN만 본다.
--   객체 유형은 AI 분석·승인으로 실행 중에 생기므로, 유형의 access_group은 관리자가 API 024로 위 그룹 이름 그대로 지정한다.
-- =====================================================================

INSERT INTO role_data_scope (role_id, access_group, can_view_restricted)
SELECT r.id, v.access_group, v.can_view_restricted
FROM role r
JOIN (VALUES
    ('PLANNER',     'PRODUCTION',   true),
    ('PLANNER',     'SALES_SUPPLY', false),
    ('VIEWER',      'PRODUCTION',   false),
    ('VIEWER',      'SALES_SUPPLY', true),
    ('MAINTENANCE', 'PRODUCTION',   false),
    ('MAINTENANCE', 'SALES_SUPPLY', false)
) AS v (role_code, access_group, can_view_restricted) ON r.code = v.role_code;

COMMENT ON TABLE role_data_scope IS '역할별 조회 범위(U5, 2026-11-06 기준·11-09 초기값 V13). ADMIN은 행 없이 전체를 보고, 그 밖의 역할은 object_type.access_group이 이 표에 있는 유형만 본다. 민감(RESTRICTED) 값은 can_view_restricted=true일 때만 본다. 행 단위 제한은 MVP 밖. 결정 11: MVP에서 MAINTENANCE는 VIEWER에 가깝게 두되 민감 값은 가린다';
