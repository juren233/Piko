import type { Env } from "../env.js";

export interface DeviceKeyRow {
  device_id: string;
  user_id: string;
  platform: string;
  device_name: string;
  ed25519_pub: unknown;
  x25519_pub: unknown;
  app_version: string | null;
  created_at: number;
  last_seen_at: number | null;
  revoked_at: number | null;
}

export interface DeviceKeyInput {
  deviceId: string;
  userId: string;
  platform: string;
  deviceName: string;
  ed25519Pub: Uint8Array;
  x25519Pub: Uint8Array;
  appVersion: string | null;
  now: number;
}

export async function upsertDeviceKey(env: Env, input: DeviceKeyInput): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO device_keys (
       device_id, user_id, platform, device_name, ed25519_pub, x25519_pub,
       app_version, created_at, last_seen_at, revoked_at
     ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, NULL, NULL)
     ON CONFLICT(device_id) DO UPDATE SET
       user_id = excluded.user_id,
       platform = excluded.platform,
       device_name = excluded.device_name,
       ed25519_pub = excluded.ed25519_pub,
       x25519_pub = excluded.x25519_pub,
       app_version = excluded.app_version,
       revoked_at = NULL`,
  )
    .bind(
      input.deviceId,
      input.userId,
      input.platform,
      input.deviceName,
      input.ed25519Pub,
      input.x25519Pub,
      input.appVersion,
      input.now,
    )
    .run();
}

export async function findActiveDevice(env: Env, deviceId: string): Promise<DeviceKeyRow | null> {
  return env.DB.prepare(
    `SELECT * FROM device_keys
     WHERE device_id = ?1 AND revoked_at IS NULL
     LIMIT 1`,
  )
    .bind(deviceId)
    .first<DeviceKeyRow>();
}

export async function listActiveDevicesForUser(env: Env, userId: string): Promise<DeviceKeyRow[]> {
  const result = await env.DB.prepare(
    `SELECT * FROM device_keys
     WHERE user_id = ?1 AND revoked_at IS NULL
     ORDER BY device_name ASC, device_id ASC`,
  )
    .bind(userId)
    .all<DeviceKeyRow>();
  return result.results ?? [];
}

export async function revokeDeviceKey(
  env: Env,
  userId: string,
  deviceId: string,
  now: number,
): Promise<boolean> {
  const result = await env.DB.prepare(
    `UPDATE device_keys
     SET revoked_at = ?1
     WHERE user_id = ?2 AND device_id = ?3 AND revoked_at IS NULL`,
  )
    .bind(now, userId, deviceId)
    .run();
  return (result.meta.changes ?? 0) > 0;
}

export function blobToBase64(value: unknown): string {
  if (typeof value === "string") return value;
  if (value instanceof ArrayBuffer) return bytesToBase64(new Uint8Array(value));
  if (value instanceof Uint8Array) return bytesToBase64(value);
  if (Array.isArray(value)) return bytesToBase64(Uint8Array.from(value as number[]));
  return "";
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}
