ALTER TABLE service_admins ADD COLUMN role text NOT NULL DEFAULT 'ADMIN'
    CHECK (role IN ('OWNER','ADMIN','MODERATOR'));
-- Preserve the explicitly bootstrapped server owner, without promoting other grants.
UPDATE service_admins s SET role='OWNER'
WHERE EXISTS (SELECT 1 FROM admin_audit a WHERE a.action='BOOTSTRAP_ADMIN' AND a.target=s.user_id::text);

CREATE TABLE voice_transcripts (
    attachment_id uuid PRIMARY KEY REFERENCES attachments(id),
    text text NOT NULL,
    language text NOT NULL,
    model text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX balance_ledger_user_created_idx ON balance_ledger(user_id,created_at DESC);
CREATE INDEX user_reports_reporter_created_idx ON user_reports(reporter_id,created_at DESC);
