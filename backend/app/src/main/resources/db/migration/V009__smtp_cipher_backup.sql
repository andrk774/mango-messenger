-- Retain original ciphertext before key migration; no plaintext passwords.
CREATE TABLE smtp_cipher_backup (
    profile_id uuid PRIMARY KEY REFERENCES smtp_profiles(id) ON DELETE RESTRICT,
    password_cipher text NOT NULL,
    saved_at timestamptz NOT NULL DEFAULT now()
);
