ALTER TABLE admin_audit ADD COLUMN archived_at TIMESTAMPTZ;
CREATE INDEX admin_audit_active_id ON admin_audit(id DESC) WHERE archived_at IS NULL;
