ALTER TABLE patients ADD COLUMN patient_number bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX patients_number_unique ON patients(patient_number);
CREATE INDEX tasks_archive_order ON tasks(scheduled_at DESC,id) WHERE status IN ('COMPLETED','CANCELLED');
INSERT INTO schema_migrations(version) VALUES (5);
