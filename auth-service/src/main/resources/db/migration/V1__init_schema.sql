-- Tenants
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) UNIQUE NOT NULL,
    plan            VARCHAR(50) NOT NULL DEFAULT 'standard',
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    max_users       INT DEFAULT 10000,
    max_channels    INT DEFAULT 1000,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Users
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20) NOT NULL DEFAULT 'member',
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, email)
);
CREATE INDEX idx_users_tenant ON users(tenant_id);

-- Channels
CREATE TABLE channels (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255),
    type            VARCHAR(20) NOT NULL,
    created_by      UUID REFERENCES users(id),
    is_archived     BOOLEAN DEFAULT FALSE,
    member_count    INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_channels_tenant ON channels(tenant_id);

-- Channel Members
CREATE TABLE channel_members (
    channel_id      UUID NOT NULL REFERENCES channels(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    role            VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_msg_id UUID,
    last_read_at    TIMESTAMPTZ,
    PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX idx_members_user ON channel_members(user_id);

-- DM Pairs (deduplication)
CREATE TABLE dm_pairs (
    tenant_id       UUID NOT NULL,
    user_id_1       UUID NOT NULL,
    user_id_2       UUID NOT NULL,
    channel_id      UUID NOT NULL REFERENCES channels(id),
    PRIMARY KEY (tenant_id, user_id_1, user_id_2)
);

-- Messages
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    channel_id      UUID NOT NULL,
    sender_id       UUID NOT NULL,
    content         TEXT,
    message_type    VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    media_url       TEXT,
    media_type      VARCHAR(100),
    is_deleted      BOOLEAN DEFAULT FALSE,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_messages_channel_time ON messages(tenant_id, channel_id, created_at DESC);
CREATE INDEX idx_messages_idempotency ON messages(idempotency_key, tenant_id);
