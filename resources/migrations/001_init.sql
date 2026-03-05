-- ============================================================
-- Chatbot Database Schema
-- ============================================================

-- 1. Users (unified registry for Telegram and MAX)
CREATE TABLE IF NOT EXISTS users (
    id                  TEXT PRIMARY KEY,          -- internal_id ("tg_12345")
    platform            TEXT NOT NULL,             -- "telegram" | "max"
    original_id         TEXT NOT NULL,             -- ID inside the platform
    username            TEXT,
    first_name          TEXT,
    last_name           TEXT,
    state               TEXT DEFAULT 'idle',       -- FSM state
    state_data          JSONB DEFAULT '{}',        -- FSM context
    chatwoot_contact_id INTEGER,
    last_bot_message_id TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_platform ON users(platform);
CREATE INDEX IF NOT EXISTS idx_users_state    ON users(state);
CREATE INDEX IF NOT EXISTS idx_users_chatwoot_contact
    ON users(chatwoot_contact_id) WHERE chatwoot_contact_id IS NOT NULL;

-- 2. Support tickets
CREATE TABLE IF NOT EXISTS tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         TEXT REFERENCES users(id) ON DELETE CASCADE,
    chatwoot_conv_id INTEGER,
    status          TEXT DEFAULT 'active',         -- active | resolved | closed
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_tickets_user           ON tickets(user_id);
CREATE INDEX IF NOT EXISTS idx_tickets_status         ON tickets(status);
CREATE INDEX IF NOT EXISTS idx_tickets_chatwoot_conv
    ON tickets(chatwoot_conv_id) WHERE chatwoot_conv_id IS NOT NULL;

-- 3. Reviews
CREATE TABLE IF NOT EXISTS reviews (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              TEXT REFERENCES users(id) ON DELETE CASCADE,
    content_text         TEXT,
    photo_urls           JSONB DEFAULT '[]',
    status               TEXT DEFAULT 'moderation', -- moderation | published | rejected
    author_name          TEXT,
    author_link          TEXT,
    moderation_message_id INTEGER,
    published_message_id  INTEGER,
    created_at           TIMESTAMPTZ DEFAULT NOW(),
    moderated_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_reviews_status ON reviews(status);
CREATE INDEX IF NOT EXISTS idx_reviews_user   ON reviews(user_id);

-- 4. Promo campaigns
CREATE TABLE IF NOT EXISTS promo_campaigns (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    description TEXT,
    promo_code  TEXT,
    max_uses    INT,                               -- NULL = unlimited
    expires_at  TIMESTAMPTZ,                      -- NULL = no expiry
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    usage_count INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_campaigns_active
    ON promo_campaigns(is_active, expires_at);

-- Placeholder campaign for legacy promo_log rows (no promo system before campaigns)
INSERT INTO promo_campaigns (id, name, is_active, description)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Исторические данные',
    false,
    'Создана автоматически для записей до введения кампаний'
) ON CONFLICT (id) DO NOTHING;

-- 5. Promo log (issued codes journal)
CREATE TABLE IF NOT EXISTS promo_log (
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    campaign_id UUID NOT NULL REFERENCES promo_campaigns(id),
    code_issued TEXT NOT NULL,
    issued_at   TIMESTAMPTZ DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, campaign_id)
);

CREATE INDEX IF NOT EXISTS idx_promo_log_campaign ON promo_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_promo_log_expires
    ON promo_log(expires_at) WHERE expires_at IS NOT NULL;

-- 6. Broadcast log (campaign notification delivery tracking)
CREATE TABLE IF NOT EXISTS broadcast_log (
    campaign_id UUID NOT NULL REFERENCES promo_campaigns(id),
    user_id     TEXT NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, user_id)
);
