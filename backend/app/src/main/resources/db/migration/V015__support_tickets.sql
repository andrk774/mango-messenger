CREATE TABLE support_tickets (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), subject VARCHAR(160) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','WAITING_USER','CLOSED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX support_tickets_user ON support_tickets(user_id, updated_at DESC);
CREATE TABLE support_messages (
 seq BIGSERIAL PRIMARY KEY, id UUID NOT NULL UNIQUE, ticket_id UUID NOT NULL REFERENCES support_tickets(id),
 author_id UUID NOT NULL REFERENCES users(id), staff BOOLEAN NOT NULL, body VARCHAR(5000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX support_messages_ticket ON support_messages(ticket_id,seq DESC);
