-- Existing rows have no owner and therefore cannot safely be assigned to any account.
DELETE FROM connections;

ALTER TABLE connections DROP CONSTRAINT IF EXISTS connections_provider_key;
ALTER TABLE connections ADD COLUMN user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE connections ADD CONSTRAINT uq_connections_user_provider UNIQUE (user_id, provider);
CREATE INDEX idx_connections_user_id ON connections(user_id);
