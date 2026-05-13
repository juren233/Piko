import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError, appErrorResponse } from "../errors.js";
import { requireAuth } from "../auth/middleware.js";
import { newId } from "../db/ids.js";
import { findUserById } from "../db/users.js";
import {
  findFriendRequestById,
  findPendingFriendRequest,
  insertFriendRequest,
  listFriendRequestsByReceiver,
  listFriendRequestsByRequester,
  setFriendRequestStatus,
  type FriendRequestRow,
  type FriendRequestWithUserRow,
} from "../db/friend_requests.js";
import { areFriends, canonicalize } from "../db/friendships.js";
import { parseFriendRequestBody } from "../validation/schemas.js";

export const friendRequestsRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

friendRequestsRoute.use("*", requireAuth);

friendRequestsRoute.post("/", async (c) => {
  const currentUser = c.get("user");
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return appErrorResponse(c, new AppError("INVALID_BODY"));
  }
  const input = parseFriendRequestBody(body);
  if (input.receiverUserId === currentUser.id) throw new AppError("SELF_FRIEND_REQUEST");
  const receiver = await findUserById(c.env, input.receiverUserId);
  if (!receiver) throw new AppError("USER_NOT_FOUND");
  if (await areFriends(c.env, currentUser.id, input.receiverUserId)) {
    throw new AppError("ALREADY_FRIENDS");
  }

  const reversePending = await findPendingFriendRequest(c.env, input.receiverUserId, currentUser.id);
  if (reversePending) {
    const accepted = await acceptPendingRequest(c.env, reversePending, currentUser.id);
    return c.json({ request: serializeRequest(accepted) }, 201);
  }

  if (await findPendingFriendRequest(c.env, currentUser.id, input.receiverUserId)) {
    throw new AppError("FRIEND_REQUEST_EXISTS");
  }

  const request: FriendRequestRow = {
    id: newId(),
    requester_user_id: currentUser.id,
    receiver_user_id: input.receiverUserId,
    status: "pending",
    created_at: Math.floor(Date.now() / 1000),
    responded_at: null,
  };
  try {
    await insertFriendRequest(c.env, {
      id: request.id,
      requesterUserId: request.requester_user_id,
      receiverUserId: request.receiver_user_id,
      createdAt: request.created_at,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes("UNIQUE")) throw new AppError("FRIEND_REQUEST_EXISTS");
    throw err;
  }

  return c.json({ request: serializeRequest(request) }, 201);
});

friendRequestsRoute.get("/", async (c) => {
  const currentUser = c.get("user");
  const direction = c.req.query("direction") === "outgoing" ? "outgoing" : "incoming";
  const rows = direction === "outgoing"
    ? await listFriendRequestsByRequester(c.env, currentUser.id)
    : await listFriendRequestsByReceiver(c.env, currentUser.id);
  return c.json({ requests: rows.map((row) => serializeRequest(row)) });
});

friendRequestsRoute.post("/:id/accept", async (c) => {
  const currentUser = c.get("user");
  const row = await loadPendingRequestForReceiver(c.env, c.req.param("id"), currentUser.id);
  const accepted = await acceptPendingRequest(c.env, row, currentUser.id);
  return c.json({ request: serializeRequest(accepted) });
});

friendRequestsRoute.post("/:id/reject", async (c) => {
  const currentUser = c.get("user");
  const row = await loadPendingRequestForReceiver(c.env, c.req.param("id"), currentUser.id);
  const rejected = await setFriendRequestStatus(c.env, row.id, "rejected", Math.floor(Date.now() / 1000));
  if (!rejected) throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  return c.json({ request: serializeRequest(rejected) });
});

friendRequestsRoute.delete("/:id", async (c) => {
  const currentUser = c.get("user");
  const row = await findFriendRequestById(c.env, c.req.param("id"));
  if (!row || row.status !== "pending") throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  if (row.requester_user_id !== currentUser.id) throw new AppError("FRIEND_REQUEST_FORBIDDEN");
  const canceled = await setFriendRequestStatus(c.env, row.id, "canceled", Math.floor(Date.now() / 1000));
  if (!canceled) throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  return c.json({ request: serializeRequest(canceled) });
});

async function loadPendingRequestForReceiver(
  env: Env,
  id: string,
  receiverUserId: string,
): Promise<FriendRequestRow> {
  const row = await findFriendRequestById(env, id);
  if (!row || row.status !== "pending") throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  if (row.receiver_user_id !== receiverUserId) throw new AppError("FRIEND_REQUEST_FORBIDDEN");
  return row;
}

async function acceptPendingRequest(
  env: Env,
  row: FriendRequestRow,
  receiverUserId: string,
): Promise<FriendRequestRow> {
  if (row.receiver_user_id !== receiverUserId) throw new AppError("FRIEND_REQUEST_FORBIDDEN");
  const now = Math.floor(Date.now() / 1000);
  const pair = canonicalize(row.requester_user_id, row.receiver_user_id);
  try {
    const results = await env.DB.batch([
      env.DB.prepare(
        `UPDATE friend_requests
         SET status = 'accepted', responded_at = ?1
         WHERE id = ?2 AND status = 'pending' AND receiver_user_id = ?3`,
      ).bind(now, row.id, receiverUserId),
      env.DB.prepare(
        `INSERT INTO friendships (id, user_a_id, user_b_id, created_at, deleted_at)
         VALUES (?1, ?2, ?3, ?4, NULL)`,
      ).bind(newId(), pair.userAId, pair.userBId, now),
    ]);
    if ((results[0]?.meta.changes ?? 0) === 0) throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  } catch (err) {
    if (err instanceof AppError) throw err;
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes("UNIQUE")) throw new AppError("ALREADY_FRIENDS");
    throw err;
  }
  const accepted = await findFriendRequestById(env, row.id);
  if (!accepted) throw new AppError("FRIEND_REQUEST_NOT_FOUND");
  return accepted;
}

function serializeRequest(row: FriendRequestRow | FriendRequestWithUserRow) {
  const base = {
    id: row.id,
    requester_user_id: row.requester_user_id,
    receiver_user_id: row.receiver_user_id,
    status: row.status,
    created_at: row.created_at,
    responded_at: row.responded_at,
  };
  if ("other_user_id" in row) {
    return {
      ...base,
      other_user: {
        id: row.other_user_id,
        username: row.other_username,
        nickname: row.other_nickname,
      },
    };
  }
  return base;
}
