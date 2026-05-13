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
