// PBKDF2-SHA256, 600_000 iter, 32-byte salt, 32-byte derived key.
// OWASP 2024 minimum baseline. WebCrypto subtle native — no WASM bloat.

import { bytesToBase64Url } from "./tokens.js";

const ITER = 600_000;
const HASH_LEN = 32;
const SALT_LEN = 32;
const ALGO = "pbkdf2-sha256";

interface PhcRecord {
  algo: string;
  iter: number;
  salt: string;
  hash: string;
}

function base64UrlToBytes(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
  const normalized = s.replace(/-/g, "+").replace(/_/g, "/") + pad;
  const bin = atob(normalized);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

async function deriveBits(password: string, salt: Uint8Array, iter: number): Promise<Uint8Array> {
  const pwBytes = new TextEncoder().encode(password);
  const key = await crypto.subtle.importKey(
    "raw",
    pwBytes,
    { name: "PBKDF2" },
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: salt as BufferSource,
      iterations: iter,
    },
    key,
    HASH_LEN * 8,
  );
  return new Uint8Array(bits);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = new Uint8Array(SALT_LEN);
  crypto.getRandomValues(salt);
  const hash = await deriveBits(password, salt, ITER);
  const record: PhcRecord = {
    algo: ALGO,
    iter: ITER,
    salt: bytesToBase64Url(salt),
    hash: bytesToBase64Url(hash),
  };
  return JSON.stringify(record);
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  let record: PhcRecord;
  try {
    record = JSON.parse(stored) as PhcRecord;
  } catch {
    return false;
  }
  if (record.algo !== ALGO || typeof record.iter !== "number" || record.iter <= 0) {
    return false;
  }
  if (typeof record.salt !== "string" || typeof record.hash !== "string") {
    return false;
  }
  const salt = base64UrlToBytes(record.salt);
  const expected = base64UrlToBytes(record.hash);
  const derived = await deriveBits(password, salt, record.iter);
  return constantTimeEqual(derived, expected);
}

function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a[i]! ^ b[i]!;
  }
  return diff === 0;
}

// 暴露常量供测试断言
export const PBKDF2_ITER = ITER;
export const PBKDF2_ALGO = ALGO;
