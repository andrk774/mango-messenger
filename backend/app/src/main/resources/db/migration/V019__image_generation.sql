CREATE TABLE ai_image_settings (id integer PRIMARY KEY CHECK(id=1), cipher text NOT NULL);
CREATE TABLE ai_image_requests (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_image_requests_quota ON ai_image_requests(user_id, created_at);
