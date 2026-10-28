-- =====================================================================
-- V3 온톨로지 → 운영 테이블 FK
-- 원본: ERD 문서 첨부 ddl_ops.sql (2026-10-27판) 8절.
-- 온톨로지 테이블의 생성·수정자, 원 제안 항목, Action 권한을 운영 테이블(account, proposal_item, permission)에 연결한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 8. 메타데이터 테이블과 연결 (같은 DB라서 FK를 건다. 스키마를 나눠도 같은 DB면 가능하다)
-- ---------------------------------------------------------------------

DO $$
DECLARE t text;
BEGIN
    -- 생성·수정자 → account
    FOREACH t IN ARRAY ARRAY['object_type', 'property_definition', 'link_type', 'data_source',
                             'object_source_mapping', 'property_source_mapping', 'action_definition'] LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (created_by) REFERENCES account (id) ON DELETE RESTRICT', t, 'fk_' || t || '_created_by');
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (updated_by) REFERENCES account (id) ON DELETE RESTRICT', t, 'fk_' || t || '_updated_by');
    END LOOP;
    FOREACH t IN ARRAY ARRAY['link_mapping', 'link_mapping_condition', 'object_instance'] LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (created_by) REFERENCES account (id) ON DELETE RESTRICT', t, 'fk_' || t || '_created_by');
    END LOOP;
    -- 원 제안 항목 → proposal_item
    FOREACH t IN ARRAY ARRAY['object_type', 'property_definition', 'link_type',
                             'object_source_mapping', 'link_mapping', 'object_link'] LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (proposal_item_id) REFERENCES proposal_item (id) ON DELETE RESTRICT', t, 'fk_' || t || '_proposal_item');
    END LOOP;
END $$;

ALTER TABLE object_link       ADD CONSTRAINT fk_object_link_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE sync_run          ADD CONSTRAINT fk_sync_run_requested_by    FOREIGN KEY (requested_by) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE data_source_credential ADD CONSTRAINT fk_data_source_credential_created_by FOREIGN KEY (created_by) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE source_connection_check ADD CONSTRAINT fk_source_connection_check_requested_by FOREIGN KEY (requested_by) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE source_preview ADD CONSTRAINT fk_source_preview_requested_by FOREIGN KEY (requested_by) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE action_definition ADD CONSTRAINT fk_action_definition_permission
    FOREIGN KEY (required_permission) REFERENCES permission (code) ON UPDATE CASCADE ON DELETE RESTRICT;
