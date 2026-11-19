CREATE TABLE service_admins (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_blocks (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    reason text NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE admin_audit (
    id bigserial PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    action text NOT NULL,
    target text NOT NULL,
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_reports (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL REFERENCES users(id),
    target_id uuid NOT NULL REFERENCES users(id),
    reason text NOT NULL CHECK (length(reason) BETWEEN 3 AND 2000),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWING','RESOLVED','REJECTED')),
    resolution text NOT NULL DEFAULT '',
    updated_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_reports_status_idx ON user_reports(status,created_at DESC);
CREATE TABLE smtp_profiles (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    host text NOT NULL,
    port integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    security text NOT NULL CHECK (security IN ('NONE','STARTTLS','TLS')),
    sender text NOT NULL,
    username text,
    password_cipher text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((username IS NULL) = (password_cipher IS NULL)),
    CHECK (username IS NULL OR security <> 'NONE')
);
CREATE TABLE server_settings (
    id integer PRIMARY KEY CHECK (id = 1),
    server_name text NOT NULL DEFAULT 'Mango Messenger',
    registration_open boolean NOT NULL DEFAULT true,
    smtp_profile_id uuid REFERENCES smtp_profiles(id)
);
INSERT INTO server_settings(id) VALUES (1);
CREATE TABLE account_balances (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    points bigint NOT NULL DEFAULT 0 CHECK (points BETWEEN 0 AND 1000000000000),
    subscription_until timestamptz
);
CREATE TABLE balance_ledger (
    request_id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    user_id uuid NOT NULL REFERENCES users(id),
    request_json text NOT NULL,
    old_points bigint NOT NULL,
    new_points bigint NOT NULL,
    old_subscription timestamptz,
    new_subscription timestamptz,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
