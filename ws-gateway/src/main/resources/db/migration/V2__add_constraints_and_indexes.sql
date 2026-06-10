-- V2: Add missing CASCADE deletes, foreign keys, and indexes

-- Add ON DELETE CASCADE to channel_members
ALTER TABLE channel_members
    DROP CONSTRAINT IF EXISTS channel_members_channel_id_fkey,
    ADD CONSTRAINT channel_members_channel_id_fkey
        FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE;

ALTER TABLE channel_members
    DROP CONSTRAINT IF EXISTS channel_members_user_id_fkey,
    ADD CONSTRAINT channel_members_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Add ON DELETE CASCADE to dm_pairs
ALTER TABLE dm_pairs
    DROP CONSTRAINT IF EXISTS dm_pairs_channel_id_fkey,
    ADD CONSTRAINT dm_pairs_channel_id_fkey
        FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE;

-- Add missing indexes for query performance
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_channels_tenant_active ON channels(tenant_id, is_archived);
CREATE INDEX IF NOT EXISTS idx_dm_pairs_reverse ON dm_pairs(tenant_id, user_id_2, user_id_1);

-- Add SET NULL on channel.created_by
ALTER TABLE channels
    DROP CONSTRAINT IF EXISTS channels_created_by_fkey,
    ADD CONSTRAINT channels_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;
