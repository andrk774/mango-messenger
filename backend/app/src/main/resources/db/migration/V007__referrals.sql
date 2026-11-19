CREATE TABLE referral_codes (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    code text NOT NULL UNIQUE
);
CREATE TABLE referrals (
    invitee_id uuid PRIMARY KEY REFERENCES users(id),
    inviter_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (invitee_id <> inviter_id)
);
CREATE INDEX referrals_inviter ON referrals(inviter_id);
