-- Add email field to user profiles
ALTER TABLE users ADD COLUMN IF NOT EXISTS email TEXT;

-- Add campaign type to distinguish subscription-based campaigns from email campaigns
ALTER TABLE promo_campaigns
  ADD COLUMN IF NOT EXISTS campaign_type TEXT NOT NULL DEFAULT 'welcome';

CREATE INDEX IF NOT EXISTS idx_campaigns_type
  ON promo_campaigns(campaign_type, is_active);
