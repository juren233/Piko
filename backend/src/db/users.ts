import type { Env } from "../env.js";

export interface UserRow {
  id: string;
  email: string;
  email_normalized: string;
  username: string;
  username_normalized: string;
  nickname: string | null;
  password_hash: string;
  email_verified_at: number | null;
  created_at: number;
  updated_at: number;
}

export async function findUserByEmailNormalized(
  env: Env,
  emailNormalized: string,
): Promise<UserRow | null> {
  return env.DB.prepare(
    `SELECT * FROM users WHERE email_normalized = ?1 LIMIT 1`,
  )
    .bind(emailNormalized)
    .first<UserRow>();
}

export async function findUserByUsernameNormalized(
  env: Env,
  usernameNormalized: string,
): Promise<UserRow | null> {
  return env.DB.prepare(
    `SELECT * FROM users WHERE username_normalized = ?1 LIMIT 1`,
  )
    .bind(usernameNormalized)
    .first<UserRow>();
}

export async function findUserById(env: Env, id: string): Promise<UserRow | null> {
  return env.DB.prepare(`SELECT * FROM users WHERE id = ?1 LIMIT 1`)
    .bind(id)
    .first<UserRow>();
}

export async function searchUsers(
  env: Env,
  query: string,
  currentUserId: string,
  limit: number,
): Promise<UserRow[]> {
  const statement = query.includes("@")
    ? env.DB.prepare(
        `SELECT * FROM users
         WHERE email_normalized = ?1 AND id <> ?2
         ORDER BY username_normalized ASC
         LIMIT ?3`,
      ).bind(query, currentUserId, limit)
    : env.DB.prepare(
        `SELECT * FROM users
         WHERE username_normalized LIKE ?1 AND id <> ?2
         ORDER BY username_normalized ASC
         LIMIT ?3`,
      ).bind(`${query}%`, currentUserId, limit);
  const result = await statement.all<UserRow>();
  return result.results ?? [];
}

export interface InsertUserParams {
  id: string;
  email: string;
  emailNormalized: string;
  username: string;
  usernameNormalized: string;
  nickname: string | null;
  passwordHash: string;
  createdAt: number;
}

export async function insertUser(env: Env, params: InsertUserParams): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO users (
       id, email, email_normalized, username, username_normalized,
       nickname, password_hash, email_verified_at, created_at, updated_at
     ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, NULL, ?8, ?8)`,
  )
    .bind(
      params.id,
      params.email,
      params.emailNormalized,
      params.username,
      params.usernameNormalized,
      params.nickname,
      params.passwordHash,
      params.createdAt,
    )
    .run();
}
