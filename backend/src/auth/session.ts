import type { Env } from "../env.js";
import { newSessionToken } from "./tokens.js";

const SESSION_TTL_SECONDS = 60 * 60 * 24 * 30; // 30 天
const KEY_PREFIX = "s:";

interface SessionRecord {
  userId: string;
  createdAt: number;
  lastSeenAt: number;
}

export async function createSession(env: Env, userId: string): Promise<string> {
  const token = newSessionToken();
  const now = Math.floor(Date.now() / 1000);
  const record: SessionRecord = { userId, createdAt: now, lastSeenAt: now };
  await env.SESSIONS.put(KEY_PREFIX + token, JSON.stringify(record), {
    expirationTtl: SESSION_TTL_SECONDS,
  });
  return token;
}

export async function loadSession(env: Env, token: string): Promise<SessionRecord | null> {
  if (!isPlausibleToken(token)) return null;
  const raw = await env.SESSIONS.get(KEY_PREFIX + token);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as SessionRecord;
    if (typeof parsed.userId !== "string") return null;
    return parsed;
  } catch {
    return null;
  }
}

export async function revokeSession(env: Env, token: string): Promise<void> {
  if (!isPlausibleToken(token)) return;
  await env.SESSIONS.delete(KEY_PREFIX + token);
}

function isPlausibleToken(token: string): boolean {
  // base64url 43 字符（32-byte payload）；只允许该字符集，避免 KV 异常 key
  return typeof token === "string" && token.length === 43 && /^[A-Za-z0-9_-]+$/.test(token);
}

export const SESSION_TTL = SESSION_TTL_SECONDS;
