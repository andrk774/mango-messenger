CREATE TABLE promo_codes (
 id uuid PRIMARY KEY, code text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]{4,40}$'),
 points bigint NOT NULL CHECK (points BETWEEN 0 AND 1000000),
 days integer NOT NULL CHECK (days BETWEEN 0 AND 3650),
 max_uses integer NOT NULL CHECK (max_uses BETWEEN 1 AND 1000000),
 used integer NOT NULL DEFAULT 0 CHECK (used >= 0 AND used <= max_uses),
 expires_at timestamptz NOT NULL, enabled boolean NOT NULL DEFAULT true,
 creator_id uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (points > 0 OR days > 0)
);
CREATE TABLE promo_redemptions (
 promo_id uuid NOT NULL REFERENCES promo_codes(id), user_id uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(promo_id,user_id)
);
CREATE TABLE cloud_contacts (
 owner_id uuid NOT NULL REFERENCES users(id), id uuid NOT NULL,
 name text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
 phone text NOT NULL DEFAULT '' CHECK (length(phone)<=64),
 email text NOT NULL DEFAULT '' CHECK (length(email)<=254),
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,id)
);
