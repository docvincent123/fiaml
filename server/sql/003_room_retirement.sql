ALTER TABLE rooms ADD COLUMN deleted_at timestamptz;
ALTER TABLE beds ADD COLUMN deleted_at timestamptz;
ALTER TABLE rooms DROP CONSTRAINT rooms_room_number_key;
ALTER TABLE beds DROP CONSTRAINT beds_room_id_bed_number_key;
CREATE UNIQUE INDEX rooms_current_number ON rooms(room_number) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX beds_current_number ON beds(room_id,bed_number) WHERE deleted_at IS NULL;
INSERT INTO schema_migrations(version) VALUES(3);
