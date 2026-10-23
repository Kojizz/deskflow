ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE account_email_tokens (
  token_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose text NOT NULL CHECK (purpose IN ('RESET_PASSWORD','LINK_PROVIDER')),
  payload jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE INDEX account_email_tokens_user ON account_email_tokens(user_id,purpose,created_at);
