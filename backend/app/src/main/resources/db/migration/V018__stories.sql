CREATE TABLE stories (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    image BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours'),
    deleted_at TIMESTAMPTZ,
    CHECK (octet_length(image) <= 1500000)
);
CREATE INDEX stories_active ON stories(expires_at, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX cloud_contacts_story_email ON cloud_contacts(owner_id, lower(email));
