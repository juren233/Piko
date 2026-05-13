import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";
import { AppError } from "../errors.js";
import { areFriends } from "../db/friendships.js";
import {
  blobToBase64,
  findActiveDevice,
  listActiveDevicesForUser,
  revokeDeviceKey,
  upsertDeviceKey,
  type DeviceKeyRow,
} from "../db/devices.js";
import { getDevicePresenceManyForUser } from "../db/presence.js";
import { parseDeviceKeyBody, parseUserIdQuery } from "../validation/schemas.js";

export const devicesRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

devicesRoute.use("*", requireAuth);

devicesRoute.post("/keys", async (c) => {
  const currentUser = c.get("user");
  const input = parseDeviceKeyBody(await c.req.json().catch(() => null));
  const now = Date.now();

  await upsertDeviceKey(c.env, {
    deviceId: input.deviceId,
    userId: currentUser.id,
    platform: input.platform,
    deviceName: input.deviceName,
    ed25519Pub: input.ed25519Pub,
    x25519Pub: input.x25519Pub,
    appVersion: input.appVersion,
    now,
  });

  const row = await findActiveDevice(c.env, input.deviceId);
  if (!row) throw new AppError("DEVICE_NOT_FOUND");

  return c.json({ device: await deviceToJson(c.env, row) }, 201);
});

devicesRoute.get("/keys", async (c) => {
  const currentUser = c.get("user");
  const targetUserId = parseUserIdQuery(c.req.query("user_id"));
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

devicesRoute.delete("/keys/:device_id", async (c) => {
  const currentUser = c.get("user");
  const deviceId = c.req.param("device_id");
  const deleted = await revokeDeviceKey(c.env, currentUser.id, deviceId, Date.now());
  if (!deleted) throw new AppError("DEVICE_NOT_FOUND");
  return c.body(null, 204);
});

export async function assertCanAccessUserDevices(
  env: Env,
  currentUserId: string,
  targetUserId: string,
): Promise<void> {
  if (currentUserId === targetUserId) return;
  if (await areFriends(env, currentUserId, targetUserId)) return;
  throw new AppError("TRANSFER_SESSION_FORBIDDEN");
}

export async function deviceToJson(env: Env, row: DeviceKeyRow) {
  const presence = await getDevicePresenceManyForUser(env, row.user_id, [row.device_id]);
  return deviceToJsonWithPresence(row, presence.get(row.device_id));
}

export function deviceToJsonWithPresence(
  row: DeviceKeyRow,
  presence: { online: boolean; lastSeenAt: number | null } | undefined,
) {
  return {
    device_id: row.device_id,
    platform: row.platform,
    device_name: row.device_name,
    ed25519_pub_b64: blobToBase64(row.ed25519_pub),
    x25519_pub_b64: blobToBase64(row.x25519_pub),
    app_version: row.app_version,
    last_seen_at: presence?.lastSeenAt ?? row.last_seen_at,
    online: presence?.online ?? false,
  };
}
