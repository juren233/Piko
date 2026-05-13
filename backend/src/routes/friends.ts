import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError } from "../errors.js";
import { canonicalize, listFriendsForUser, softDeleteFriendship } from "../db/friendships.js";
import { requireAuth } from "../auth/middleware.js";
import { assertCanAccessUserDevices, deviceToJsonWithPresence } from "./devices.js";
import { listActiveDevicesForUser } from "../db/devices.js";
import { getDevicePresenceManyForUser } from "../db/presence.js";

export const friendsRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

friendsRoute.use("*", requireAuth);

friendsRoute.get("/", async (c) => {
  const currentUser = c.get("user");
  const friends = await listFriendsForUser(c.env, currentUser.id);
  const presence = await getUserPresenceFromDevices(
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
  const presence = await getDevicePresenceManyForUser(
    c.env,
    targetUserId,
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

async function getUserPresenceFromDevices(
  env: Env,
  userIds: string[],
): Promise<Map<string, { online: boolean; lastSeenAt: number | null }>> {
  const devicesByUser = await Promise.all(
    userIds.map(async (userId) => [userId, await listActiveDevicesForUser(env, userId)] as const),
  );
  const presenceByUser = new Map(
    await Promise.all(
      devicesByUser.map(async ([userId, userDevices]) => [
        userId,
        await getDevicePresenceManyForUser(
          env,
          userId,
          userDevices.map((device) => device.device_id),
        ),
      ] as const),
    ),
  );
  return new Map(
    devicesByUser.map(([userId, userDevices]) => {
      const devicePresence = presenceByUser.get(userId) ?? new Map();
      const statuses = userDevices.map((device) => devicePresence.get(device.device_id));
      const onlineStatuses = statuses.filter((status) => status?.online);
      const online = onlineStatuses.length > 0;
      const lastSeenAt = statuses.reduce<number | null>((latest, status) => {
        const seenAt = status?.lastSeenAt ?? null;
        if (seenAt === null) return latest;
        return latest === null ? seenAt : Math.max(latest, seenAt);
      }, null);
      return [userId, { online, lastSeenAt }] as const;
    }),
  );
}
