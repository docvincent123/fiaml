ALTER TABLE sessions ADD COLUMN alerts_seen_at timestamptz;
INSERT INTO schema_migrations(version) VALUES(7);
