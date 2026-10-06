-- The System Register apply (stories 2, 3, 4, 73 and 79): a confirmed preview writes the Systems, and the SMO/Tech Lead
-- and Cyber Manager role grants follow the Register.
--
-- role_grant.system_id now references va_system: a role for a System is for a System that exists. Retiring a System
-- keeps its row, so history and audit entries keep resolving; nothing deletes a System.
ALTER TABLE role_grant
    ADD CONSTRAINT role_grant_system_fk FOREIGN KEY (system_id) REFERENCES va_system (id);

CREATE INDEX role_grant_system_idx ON role_grant (system_id) WHERE system_id IS NOT NULL;

-- register_upload: who applied the preview and when. A preview is applied at most once; the apply refuses a preview
-- whose status is no longer PREVIEW.
ALTER TABLE register_upload
    ADD COLUMN applied_at timestamptz,
    ADD COLUMN applied_by uuid REFERENCES app_user (id),
    ADD CONSTRAINT register_upload_applied_check
        CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL AND applied_by IS NOT NULL));

-- The audit log learns the counts an apply records: Systems added, updated, retired and left as they were, and the
-- role grants given and revoked. Numbers only, never a name or an email.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked'] = '{}'::jsonb);
