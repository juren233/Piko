import type { Env } from "../env.js";

export type FriendRequestStatus = "pending" | "accepted" | "rejected" | "canceled";

export interface FriendRequestRow {
  id: string;
  requester_user_id: string;
  receiver_user_id: string;
  status: FriendRequestStatus;
  created_at: number;
  responded_at: number | null;
}

export interface FriendRequestWithUserRow extends FriendRequestRow {
  other_user_id: string;
  other_username: string;
  other_nickname: string | null;
}

export interface InsertFriendRequestParams {
  id: string;
  requesterUserId: string;
  receiverUserId: string;
  createdAt: number;
}

export async function insertFriendRequest(env: Env, params: InsertFriendRequestParams): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO friend_requests (
       id, requester_user_id, receiver_user_id, status, created_at, responded_at
     ) VALUES (?1, ?2, ?3, 'pending', ?4, NULL)`,
  )
    .bind(params.id, params.requesterUserId, params.receiverUserId, params.createdAt)
    .run();
}

export async function findFriendRequestById(env: Env, id: string): Promise<FriendRequestRow | null> {
  return env.DB.prepare(`SELECT * FROM friend_requests WHERE id = ?1 LIMIT 1`)
    .bind(id)
    .first<FriendRequestRow>();
}

export async function findPendingFriendRequest(
  env: Env,
  requesterUserId: string,
  receiverUserId: string,
): Promise<FriendRequestRow | null> {
  return env.DB.prepare(
    `SELECT * FROM friend_requests
     WHERE requester_user_id = ?1 AND receiver_user_id = ?2 AND status = 'pending'
     LIMIT 1`,
  )
    .bind(requesterUserId, receiverUserId)
    .first<FriendRequestRow>();
}

export async function setFriendRequestStatus(
  env: Env,
  id: string,
  status: FriendRequestStatus,
  respondedAt: number,
): Promise<FriendRequestRow | null> {
  const result = await env.DB.prepare(
    `UPDATE friend_requests
     SET status = ?1, responded_at = ?2
     WHERE id = ?3 AND status = 'pending'`,
  )
    .bind(status, respondedAt, id)
    .run();
  if ((result.meta.changes ?? 0) === 0) return null;
  return findFriendRequestById(env, id);
}

export async function listFriendRequestsByReceiver(
  env: Env,
  userId: string,
): Promise<FriendRequestWithUserRow[]> {
  const result = await env.DB.prepare(
    `SELECT
       fr.*,
       u.id AS other_user_id,
       u.username AS other_username,
       u.nickname AS other_nickname
     FROM friend_requests fr
     JOIN users u ON u.id = fr.requester_user_id
     WHERE fr.receiver_user_id = ?1
     ORDER BY fr.created_at DESC
     LIMIT 100`,
  )
    .bind(userId)
    .all<FriendRequestWithUserRow>();
  return result.results ?? [];
}

export async function listFriendRequestsByRequester(
  env: Env,
  userId: string,
): Promise<FriendRequestWithUserRow[]> {
  const result = await env.DB.prepare(
    `SELECT
       fr.*,
       u.id AS other_user_id,
       u.username AS other_username,
       u.nickname AS other_nickname
     FROM friend_requests fr
     JOIN users u ON u.id = fr.receiver_user_id
     WHERE fr.requester_user_id = ?1
     ORDER BY fr.created_at DESC
     LIMIT 100`,
  )
    .bind(userId)
    .all<FriendRequestWithUserRow>();
  return result.results ?? [];
}
