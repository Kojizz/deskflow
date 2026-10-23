ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
CREATE TABLE oauth_identities (
  provider text NOT NULL CHECK (provider IN ('google','github')),
  subject text NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(provider,subject),
  UNIQUE(user_id,provider)
);
