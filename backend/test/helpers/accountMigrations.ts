import type { D1Migration } from "cloudflare:test";

export const testAccountMigrations: D1Migration[] = [
  {
    name: "0001_init_accounts.sql",
    queries: [
      `CREATE TABLE users (
  id                  TEXT PRIMARY KEY,
  email               TEXT NOT NULL,
  email_normalized    TEXT NOT NULL,
  username            TEXT NOT NULL,
  username_normalized TEXT NOT NULL,
  nickname            TEXT,
  password_hash       TEXT NOT NULL,
  email_verified_at   INTEGER,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL
)`,
      "CREATE UNIQUE INDEX ux_users_email_normalized ON users(email_normalized)",
      "CREATE UNIQUE INDEX ux_users_username_normalized ON users(username_normalized)",
    ],
  },
  {
    name: "0002_friends_and_presence.sql",
    queries: [
      `CREATE TABLE friend_requests (
  id                  TEXT PRIMARY KEY,
  requester_user_id   TEXT NOT NULL,
  receiver_user_id    TEXT NOT NULL,
  status              TEXT NOT NULL,
  created_at          INTEGER NOT NULL,
  responded_at        INTEGER,
  FOREIGN KEY (requester_user_id) REFERENCES users(id),
  FOREIGN KEY (receiver_user_id)  REFERENCES users(id)
)`,
      "CREATE INDEX ix_fr_receiver_status ON friend_requests(receiver_user_id, status)",
      "CREATE INDEX ix_fr_requester_status ON friend_requests(requester_user_id, status)",
      `CREATE UNIQUE INDEX ux_fr_pair_pending
  ON friend_requests(requester_user_id, receiver_user_id)
  WHERE status = 'pending'`,
      `CREATE TABLE friendships (
  id          TEXT PRIMARY KEY,
  user_a_id   TEXT NOT NULL,
  user_b_id   TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  deleted_at  INTEGER,
  FOREIGN KEY (user_a_id) REFERENCES users(id),
  FOREIGN KEY (user_b_id) REFERENCES users(id)
)`,
      `CREATE UNIQUE INDEX ux_friendships_pair_active
  ON friendships(user_a_id, user_b_id)
  WHERE deleted_at IS NULL`,
      "CREATE INDEX ix_friendships_user_a ON friendships(user_a_id) WHERE deleted_at IS NULL",
      "CREATE INDEX ix_friendships_user_b ON friendships(user_b_id) WHERE deleted_at IS NULL",
    ],
  },
  {
    name: "0003_device_keys_and_signaling.sql",
    queries: [
      `CREATE TABLE device_keys (
  device_id        TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL,
  platform         TEXT NOT NULL,
  device_name      TEXT NOT NULL,
  ed25519_pub      BLOB NOT NULL,
  x25519_pub       BLOB NOT NULL,
  app_version      TEXT,
  created_at       INTEGER NOT NULL,
  last_seen_at     INTEGER,
  revoked_at       INTEGER,
  FOREIGN KEY (user_id) REFERENCES users(id)
)`,
      `CREATE INDEX ix_device_keys_user_active
  ON device_keys(user_id) WHERE revoked_at IS NULL`,
    ],
  },
];
