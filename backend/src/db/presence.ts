import type { Env } from "../env.js";

const PRESENCE_PREFIX = "p:";
const PRESENCE_TTL_SECONDS = 90;

interface PresenceRecord {
  last_seen_at: number;
  device_count: number;
}

export interface PresenceStatus {
  online: boolean;
  lastSeenAt: number | null;
}

export async function touchPresence(env: Env, userId: string, now: number): Promise<void> {
  const record: PresenceRecord = { last_seen_at: now, device_count: 1 };
  await env.SESSIONS.put(PRESENCE_PREFIX + userId, JSON.stringify(record), {
    expirationTtl: PRESENCE_TTL_SECONDS,
  });
}

export async function getPresenceMany(
  env: Env,
  userIds: string[],
): Promise<Map<string, PresenceStatus>> {
  const entries = await Promise.all(
    userIds.map(async (userId) => [userId, await getPresence(env, userId)] as const),
  );
  return new Map(entries);
}

export async function getDevicePresenceManyForUser(
  env: Env,
  userId: string,
  deviceIds: string[],
): Promise<Map<string, PresenceStatus>> {
  if (deviceIds.length === 0 || !env.SIGNALING_HUB) {
    return new Map(deviceIds.map((deviceId) => [deviceId, { online: false, lastSeenAt: null }] as const));
  }
  const id = env.SIGNALING_HUB.idFromName(userId);
  const response = await env.SIGNALING_HUB.get(id).fetch("https://signaling.local/presence", {
    method: "POST",
    body: JSON.stringify({ device_ids: deviceIds }),
  });
  const payload = (await response.json().catch(() => ({ devices: [] }))) as {
    devices?: Array<{ device_id?: unknown; online?: unknown; last_seen_at?: unknown }>;
  };
  const statuses = new Map<string, PresenceStatus>(
    deviceIds.map((deviceId) => [deviceId, { online: false, lastSeenAt: null }] as const),
  );
  for (const device of payload.devices ?? []) {
    if (typeof device.device_id !== "string") continue;
    statuses.set(device.device_id, {
      online: device.online === true,
      lastSeenAt: typeof device.last_seen_at === "number" ? device.last_seen_at : null,
    });
  }
  return statuses;
}

async function getPresence(env: Env, userId: string): Promise<PresenceStatus> {
  const raw = await env.SESSIONS.get(PRESENCE_PREFIX + userId);
  if (!raw) return { online: false, lastSeenAt: null };
  try {
    const parsed = JSON.parse(raw) as PresenceRecord;
    if (typeof parsed.last_seen_at !== "number") {
      return { online: false, lastSeenAt: null };
    }
    return { online: true, lastSeenAt: parsed.last_seen_at };
  } catch {
    return { online: false, lastSeenAt: null };
  }
}
