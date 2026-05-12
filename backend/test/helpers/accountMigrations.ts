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
];
