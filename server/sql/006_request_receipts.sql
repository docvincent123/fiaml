CREATE TABLE request_receipts (
 actor_id uuid NOT NULL REFERENCES users(id),
 request_id uuid NOT NULL,
 payload_hash text NOT NULL,
 response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id,request_id)
);
INSERT INTO schema_migrations(version) VALUES(6);
