CREATE TABLE kanban_boards (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id),
 conversation_id uuid REFERENCES conversations(id), title varchar(120) NOT NULL,
 columns_json jsonb NOT NULL, version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX kanban_boards_conversation ON kanban_boards(conversation_id);
CREATE TABLE kanban_cards (
 board_id uuid NOT NULL REFERENCES kanban_boards(id) ON DELETE CASCADE,
 task_id uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 column_id varchar(64) NOT NULL, position integer NOT NULL,
 tags jsonb NOT NULL DEFAULT '[]', PRIMARY KEY(board_id,task_id)
);
CREATE TABLE kanban_events (
 id bigserial PRIMARY KEY, board_id uuid NOT NULL REFERENCES kanban_boards(id) ON DELETE CASCADE,
 actor_id uuid NOT NULL REFERENCES users(id), detail jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX kanban_events_board ON kanban_events(board_id,id);
CREATE TABLE kanban_reads (
 board_id uuid NOT NULL REFERENCES kanban_boards(id) ON DELETE CASCADE,
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 event_id bigint NOT NULL, PRIMARY KEY(board_id,user_id)
);
