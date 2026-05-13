import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError } from "../errors.js";
import { canonicalize, listFriendsForUser, softDeleteFriendship } from "../db/friendships.js";
import { getPresenceMany } from "../db/presence.js";
import { requireAuth } from "../auth/middleware.js";
import { assertCanAccessUserDevices, deviceToJsonWithPresence } from "./devices.js";
import { listActiveDevicesForUser } from "../db/devices.js";
import { getDevicePresenceMany } from "../db/presence.js";

export const friendsRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

friendsRoute.use("*", requireAuth);

friendsRoute.get("/", async (c) => {
  const currentUser = c.get("user");
  const friends = await listFriendsForUser(c.env, currentUser.id);
  const presence = await getPresenceMany(
    c.env,
    friends.map((friend) => friend.user_id),
  );
  return c.json({
    friends: friends.map((friend) => {
      const status = presence.get(friend.user_id) ?? { online: false, lastSeenAt: null };
      return {
        user_id: friend.user_id,
        username: friend.username,
        nickname: friend.nickname,
        online: status.online,
        last_seen_at: status.lastSeenAt,
        since: friend.since,
      };
    }),
  });
});

friendsRoute.get("/:user_id/devices", async (c) => {
  const currentUser = c.get("user");
  const targetUserId = c.req.param("user_id");
  await assertCanAccessUserDevices(c.env, currentUser.id, targetUserId);
  const devices = await listActiveDevicesForUser(c.env, targetUserId);
  const presence = await getDevicePresenceMany(
    c.env,
    devices.map((device) => device.device_id),
  );
  return c.json({
    devices: devices.map((device) => deviceToJsonWithPresence(device, presence.get(device.device_id))),
  });
});

friendsRoute.delete("/:user_id", async (c) => {
  const currentUser = c.get("user");
  const targetUserId = c.req.param("user_id");
  const pair = canonicalize(currentUser.id, targetUserId);
  if (pair.userAId === pair.userBId) throw new AppError("USER_NOT_FOUND");
  const deleted = await softDeleteFriendship(
    c.env,
    currentUser.id,
    targetUserId,
    Math.floor(Date.now() / 1000),
  );
  if (!deleted) throw new AppError("USER_NOT_FOUND");
  return new Response(null, { status: 204 });
});
