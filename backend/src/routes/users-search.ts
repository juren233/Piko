import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { parseSearchQuery } from "../validation/schemas.js";
import { searchUsers } from "../db/users.js";
import { areFriends } from "../db/friendships.js";
import { findPendingFriendRequest } from "../db/friend_requests.js";
import { requireAuth } from "../auth/middleware.js";

type Relationship = "none" | "pending-out" | "pending-in" | "friend";

export const usersSearchRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

usersSearchRoute.use("*", requireAuth);

usersSearchRoute.get("/", async (c) => {
  const currentUser = c.get("user");
  const query = parseSearchQuery(c.req.query("q"));
  const users = await searchUsers(c.env, query, currentUser.id, 20);
  const results = await Promise.all(
    users.map(async (user) => ({
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      relationship: await resolveRelationship(c.env, currentUser.id, user.id),
    })),
  );
  return c.json({ results });
});

async function resolveRelationship(
  env: Env,
  currentUserId: string,
  otherUserId: string,
): Promise<Relationship> {
  if (await areFriends(env, currentUserId, otherUserId)) return "friend";
  if (await findPendingFriendRequest(env, currentUserId, otherUserId)) return "pending-out";
  if (await findPendingFriendRequest(env, otherUserId, currentUserId)) return "pending-in";
  return "none";
}
