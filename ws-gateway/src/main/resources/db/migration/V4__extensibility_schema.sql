-- V4: Extensibility schema — pre-add columns + tables for future Slack features.
-- All new columns are nullable, zero behavior change until a feature uses them.

-- ═══ Messages: threads + editing ═══
ALTER TABLE messages ADD COLUMN IF NOT EXISTS parent_message_id UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_count INT DEFAULT 0;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_messages_parent ON messages(parent_message_id) WHERE parent_message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages(tenant_id, parent_message_id, created_at DESC) WHERE parent_message_id IS NOT NULL;

-- ═══ Channels: topic + description ═══
ALTER TABLE channels ADD COLUMN IF NOT EXISTS topic TEXT;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS description TEXT;

-- ═══ Users: profile ═══
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(2048);
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_text VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone VARCHAR(50);

-- ═══ Channel Members: notification preferences ═══
ALTER TABLE channel_members ADD COLUMN IF NOT EXISTS muted BOOLEAN DEFAULT FALSE;
ALTER TABLE channel_members ADD COLUMN IF NOT EXISTS notification_level VARCHAR(20) DEFAULT 'default';

-- ═══ Reactions ═══
CREATE TABLE IF NOT EXISTS reactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    message_id UUID NOT NULL,
    user_id UUID NOT NULL,
    emoji VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(message_id, user_id, emoji)
);
CREATE INDEX IF NOT EXISTS idx_reactions_message ON reactions(message_id);
CREATE INDEX IF NOT EXISTS idx_reactions_tenant ON reactions(tenant_id);

-- ═══ Pinned Messages ═══
CREATE TABLE IF NOT EXISTS pinned_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    message_id UUID NOT NULL,
    pinned_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(channel_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_pinned_channel ON pinned_messages(channel_id);

-- ═══ Starred Items (bookmarks) ═══
CREATE TABLE IF NOT EXISTS starred_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    item_type VARCHAR(50) NOT NULL,
    item_id UUID NOT NULL,
    channel_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, item_type, item_id)
);
CREATE INDEX IF NOT EXISTS idx_starred_user ON starred_items(tenant_id, user_id);
