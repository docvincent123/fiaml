CREATE TABLE IF NOT EXISTS schema_migrations(version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE users(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text NOT NULL, login text UNIQUE NOT NULL,
 password_hash text NOT NULL, role text NOT NULL CHECK(role IN ('ADMIN','REGISTRAR','DOCTOR','NURSE')), specialty text NOT NULL DEFAULT '',
 permissions jsonb NOT NULL DEFAULT '{}', active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE sessions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users, device text NOT NULL, ip text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz);
CREATE INDEX ON sessions(user_id) WHERE revoked_at IS NULL;
CREATE TABLE login_attempts(key text PRIMARY KEY, failures integer NOT NULL DEFAULT 0, window_at timestamptz NOT NULL DEFAULT now(), blocked_until timestamptz);
CREATE TABLE shifts(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users, starts_at timestamptz NOT NULL DEFAULT now(), ends_at timestamptz NOT NULL, CHECK(ends_at>starts_at));
CREATE INDEX ON shifts(user_id,ends_at);
CREATE TABLE rooms(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), room_number text UNIQUE NOT NULL);
CREATE TABLE beds(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), room_id uuid NOT NULL REFERENCES rooms, bed_number text NOT NULL,
 qr_uid uuid UNIQUE NOT NULL DEFAULT gen_random_uuid(), UNIQUE(room_id,bed_number));
CREATE TABLE patients(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text NOT NULL, birth_date date, phone text NOT NULL DEFAULT '', status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE admissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid NOT NULL REFERENCES patients, bed_id uuid REFERENCES beds,
 doctor_id uuid REFERENCES users, admitted_at timestamptz NOT NULL DEFAULT now(), discharged_at timestamptz);
CREATE UNIQUE INDEX active_patient ON admissions(patient_id) WHERE discharged_at IS NULL;
CREATE UNIQUE INDEX active_bed ON admissions(bed_id) WHERE discharged_at IS NULL;
CREATE TABLE medical_notes(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid NOT NULL REFERENCES patients, admission_id uuid NOT NULL REFERENCES admissions,
 author_id uuid NOT NULL REFERENCES users, body text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE cabinets(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE NOT NULL, type text NOT NULL CHECK(type IN ('massage','pool','gym','physio')));
CREATE TABLE appointments(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid NOT NULL REFERENCES patients, cabinet_id uuid NOT NULL REFERENCES cabinets,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, status text NOT NULL DEFAULT 'BOOKED' CHECK(status IN ('BOOKED','CANCELLED','COMPLETED')), created_by uuid NOT NULL REFERENCES users,
 CHECK(ends_at>starts_at));
CREATE INDEX ON appointments(cabinet_id,starts_at,ends_at) WHERE status='BOOKED';
CREATE TABLE tasks(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid NOT NULL REFERENCES patients, admission_id uuid NOT NULL REFERENCES admissions,
 created_by uuid NOT NULL REFERENCES users, taken_by uuid REFERENCES users, status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')),
 description text NOT NULL, task_type text NOT NULL, scheduled_at timestamptz NOT NULL, cabinet_id uuid REFERENCES cabinets, created_at timestamptz NOT NULL DEFAULT now(), taken_at timestamptz, completed_at timestamptz,
 CHECK((status='OPEN' AND taken_by IS NULL AND taken_at IS NULL AND completed_at IS NULL) OR (status='IN_PROGRESS' AND taken_by IS NOT NULL AND taken_at IS NOT NULL AND completed_at IS NULL) OR (status='COMPLETED' AND taken_by IS NOT NULL AND taken_at IS NOT NULL AND completed_at IS NOT NULL) OR status='CANCELLED'));
CREATE INDEX ON tasks(status,scheduled_at); CREATE INDEX ON tasks(taken_by,status);
CREATE TABLE audit_events(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor_id uuid REFERENCES users, action text NOT NULL, entity_id uuid, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE outbox(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz);
INSERT INTO schema_migrations(version) VALUES(1);
