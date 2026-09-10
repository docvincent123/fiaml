ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK(role IN ('ADMIN','REGISTRAR','DOCTOR','NURSE','THERAPIST'));
ALTER TABLE patients ADD COLUMN address text NOT NULL DEFAULT '', ADD COLUMN emergency_contact text NOT NULL DEFAULT '';
ALTER TABLE admissions ADD COLUMN referral text NOT NULL DEFAULT '', ADD COLUMN care_type text NOT NULL DEFAULT 'INPATIENT' CHECK(care_type IN ('INPATIENT','OUTPATIENT')),
 ADD COLUMN doctor_since timestamptz NOT NULL DEFAULT now();
UPDATE admissions SET doctor_since=admitted_at;
ALTER TABLE rooms ADD COLUMN floor text NOT NULL DEFAULT '';
CREATE TABLE care_handovers(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), admission_id uuid NOT NULL REFERENCES admissions,
 from_id uuid NOT NULL REFERENCES users, to_id uuid NOT NULL REFERENCES users, summary text NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now(), accepted_at timestamptz, CHECK(from_id<>to_id));
CREATE UNIQUE INDEX pending_handover ON care_handovers(admission_id) WHERE status='PENDING';
CREATE TABLE clinical_entries(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), admission_id uuid NOT NULL REFERENCES admissions,
 author_id uuid NOT NULL REFERENCES users, kind text NOT NULL CHECK(kind IN ('ASSESSMENT','OBSERVATION','REHAB','DISCHARGE','CORRECTION')),
 body text NOT NULL, data jsonb NOT NULL DEFAULT '{}', corrects_id uuid REFERENCES clinical_entries, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ON clinical_entries(admission_id,created_at);
CREATE TABLE patient_documents(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid NOT NULL REFERENCES patients,
 admission_id uuid REFERENCES admissions, author_id uuid NOT NULL REFERENCES users, filename text NOT NULL,
 mime text NOT NULL CHECK(mime IN ('application/pdf','image/jpeg','image/png')), content bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE care_messages(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), patient_id uuid REFERENCES patients,
 sender_id uuid NOT NULL REFERENCES users, recipient_id uuid NOT NULL REFERENCES users, body text NOT NULL,
 urgent boolean NOT NULL DEFAULT false, read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ON care_messages(recipient_id,created_at);
CREATE TABLE team_handovers(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), author_id uuid NOT NULL REFERENCES users,
 recipient_id uuid NOT NULL REFERENCES users, summary text NOT NULL, task_ids uuid[] NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now(), accepted_at timestamptz);
ALTER TABLE tasks ADD COLUMN executor_role text NOT NULL DEFAULT 'NURSE' CHECK(executor_role IN ('NURSE','THERAPIST')),
 ADD COLUMN medication text NOT NULL DEFAULT '', ADD COLUMN dose text NOT NULL DEFAULT '', ADD COLUMN dose_unit text NOT NULL DEFAULT '',
 ADD COLUMN route text NOT NULL DEFAULT '', ADD COLUMN course_id uuid, ADD COLUMN outcome text NOT NULL DEFAULT '',
 ADD COLUMN not_done boolean NOT NULL DEFAULT false;
ALTER TABLE appointments ADD COLUMN staff_id uuid REFERENCES users;
CREATE TABLE patient_revisions(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, patient_id uuid NOT NULL REFERENCES patients,
 actor_id uuid NOT NULL REFERENCES users, previous jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO schema_migrations(version) VALUES(2);
