import type { Env } from "../env.js";

export interface FriendRow {
  friendship_id: string;
  user_id: string;
  username: string;
  nickname: string | null;
  since: number;
}

export interface CanonicalPair {
  userAId: string;
  userBId: string;
}

export function canonicalize(a: string, b: string): CanonicalPair {
  return a < b ? { userAId: a, userBId: b } : { userAId: b, userBId: a };
}

export async function insertFriendship(
  env: Env,
  id: string,
  pair: CanonicalPair,
  createdAt: number,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO friendships (id, user_a_id, user_b_id, created_at, deleted_at)
     VALUES (?1, ?2, ?3, ?4, NULL)`,
  )
    .bind(id, pair.userAId, pair.userBId, createdAt)
    .run();
}

export async function areFriends(env: Env, a: string, b: string): Promise<boolean> {
  const pair = canonicalize(a, b);
  const row = await env.DB.prepare(
    `SELECT id FROM friendships
     WHERE user_a_id = ?1 AND user_b_id = ?2 AND deleted_at IS NULL
     LIMIT 1`,
  )
    .bind(pair.userAId, pair.userBId)
    .first<{ id: string }>();
  return row !== null;
}

export async function listFriendsForUser(env: Env, userId: string): Promise<FriendRow[]> {
  const result = await env.DB.prepare(
    `SELECT
       f.id AS friendship_id,
       u.id AS user_id,
       u.username AS username,
       u.nickname AS nickname,
       f.created_at AS since
     FROM friendships f
     JOIN users u ON u.id = CASE WHEN f.user_a_id = ?1 THEN f.user_b_id ELSE f.user_a_id END
     WHERE (f.user_a_id = ?1 OR f.user_b_id = ?1) AND f.deleted_at IS NULL
     ORDER BY u.username_normalized ASC`,
  )
    .bind(userId)
    .all<FriendRow>();
  return result.results ?? [];
}

export async function softDeleteFriendship(
  env: Env,
  selfUserId: string,
  targetUserId: string,
  deletedAt: number,
): Promise<boolean> {
  const pair = canonicalize(selfUserId, targetUserId);
  const result = await env.DB.prepare(
    `UPDATE friendships
     SET deleted_at = ?1
     WHERE user_a_id = ?2 AND user_b_id = ?3 AND deleted_at IS NULL`,
  )
    .bind(deletedAt, pair.userAId, pair.userBId)
    .run();
  return (result.meta.changes ?? 0) > 0;
}
