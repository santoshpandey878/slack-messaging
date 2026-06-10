-- Add sender_name to messages (denormalized for fast history display)
ALTER TABLE messages ADD COLUMN IF NOT EXISTS sender_name VARCHAR(255);
