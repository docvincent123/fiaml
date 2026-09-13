ALTER TABLE users ADD COLUMN role_label text NOT NULL DEFAULT '';
ALTER TABLE admissions ADD COLUMN complaints text NOT NULL DEFAULT '';
ALTER TABLE patients ADD COLUMN sex text NOT NULL DEFAULT 'UNKNOWN' CHECK(sex IN ('FEMALE','MALE','OTHER','UNKNOWN'));
ALTER TABLE cabinets DROP CONSTRAINT IF EXISTS cabinets_type_check;
CREATE TABLE shift_requests(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 requested_at timestamptz NOT NULL DEFAULT now(), decided_by uuid REFERENCES users, decided_at timestamptz);
CREATE UNIQUE INDEX shift_pending_user ON shift_requests(user_id) WHERE status='PENDING';
INSERT INTO schema_migrations(version) VALUES(4);
