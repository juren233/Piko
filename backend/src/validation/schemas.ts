import { AppError } from "../errors.js";

// 邮箱：trim + lowercase；本地校验 + 长度上限 RFC 5321 254。
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
// 用户名：NFKC 之后允许字母数字下划线/横线 + CJK 统一汉字。3..32 字符。
const USERNAME_REGEX = /^[a-zA-Z0-9_\-一-鿿]+$/;
// 控制字符（昵称黑名单）— 检测 ASCII NUL..US 与 DEL
function containsControlChar(s: string): boolean {
  for (let i = 0; i < s.length; i += 1) {
    const c = s.charCodeAt(i);
    if (c <= 0x1f || c === 0x7f) return true;
  }
  return false;
}

export interface RegisterInput {
  email: string;
  emailNormalized: string;
  password: string;
  username: string;
  usernameNormalized: string;
  nickname: string | null;
}

export interface LoginInput {
  email: string;
  emailNormalized: string;
  password: string;
}

export interface FriendRequestInput {
  receiverUserId: string;
}

export interface DeviceKeyInput {
  deviceId: string;
  platform: string;
  deviceName: string;
  ed25519Pub: Uint8Array;
  x25519Pub: Uint8Array;
  appVersion: string | null;
}

export interface TransferSessionInput {
  receiverUserId: string;
  receiverDeviceId: string;
  transferId: string;
  manifestHashB64: string;
  senderX25519EphPubB64: string;
  senderDeviceId: string | null;
  senderInviteSignatureB64: string;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function requireString(body: Record<string, unknown>, key: string): string | null {
  const v = body[key];
  if (typeof v !== "string") return null;
  return v;
}

export function parseRegister(body: unknown): RegisterInput {
  if (!isObject(body)) throw new AppError("INVALID_BODY");

  const rawEmail = requireString(body, "email");
  if (rawEmail === null) throw new AppError("INVALID_EMAIL");
  const email = rawEmail.trim();
  const emailNormalized = email.toLowerCase();
  if (emailNormalized.length === 0 || emailNormalized.length > 254 || !EMAIL_REGEX.test(emailNormalized)) {
    throw new AppError("INVALID_EMAIL");
  }

  const password = requireString(body, "password");
  if (password === null) throw new AppError("INVALID_PASSWORD");
  if (password.length < 8 || password.length > 128 || password.trim().length === 0) {
    throw new AppError("INVALID_PASSWORD");
  }

  const rawUsername = requireString(body, "username");
  if (rawUsername === null) throw new AppError("INVALID_USERNAME");
  const username = rawUsername.normalize("NFKC").trim();
  if (username.length < 3 || username.length > 32 || !USERNAME_REGEX.test(username)) {
    throw new AppError("INVALID_USERNAME");
  }
  const usernameNormalized = username.toLowerCase();

  let nickname: string | null = null;
  const rawNickname = body["nickname"];
  if (rawNickname !== undefined && rawNickname !== null) {
    if (typeof rawNickname !== "string") throw new AppError("INVALID_NICKNAME");
    const trimmed = rawNickname.normalize("NFKC").trim();
    if (trimmed.length > 0) {
      if (trimmed.length > 48 || containsControlChar(trimmed)) {
        throw new AppError("INVALID_NICKNAME");
      }
      nickname = trimmed;
    }
  }

  return { email, emailNormalized, password, username, usernameNormalized, nickname };
}

export function parseLogin(body: unknown): LoginInput {
  if (!isObject(body)) throw new AppError("INVALID_BODY");

  const rawEmail = requireString(body, "email");
  if (rawEmail === null) throw new AppError("INVALID_CREDENTIALS");
  const email = rawEmail.trim();
  const emailNormalized = email.toLowerCase();
  if (emailNormalized.length === 0 || emailNormalized.length > 254 || !EMAIL_REGEX.test(emailNormalized)) {
    throw new AppError("INVALID_CREDENTIALS");
  }

  const password = requireString(body, "password");
  if (password === null || password.length === 0) {
    throw new AppError("INVALID_CREDENTIALS");
  }

  return { email, emailNormalized, password };
}

export function parseSearchQuery(raw: string | undefined): string {
  if (raw === undefined) throw new AppError("INVALID_SEARCH_QUERY");
  const query = raw.normalize("NFKC").trim();
  if (query.length < 2 || query.length > 254) {
    throw new AppError("INVALID_SEARCH_QUERY");
  }
  if (query.includes("@")) {
    const normalized = query.toLowerCase();
    if (!EMAIL_REGEX.test(normalized)) throw new AppError("INVALID_SEARCH_QUERY");
    return normalized;
  }
  if (!USERNAME_REGEX.test(query)) throw new AppError("INVALID_SEARCH_QUERY");
  return query.toLowerCase();
}

export function parseFriendRequestBody(body: unknown): FriendRequestInput {
  if (!isObject(body)) throw new AppError("INVALID_BODY");
  const receiverUserId = requireString(body, "receiver_user_id");
  if (receiverUserId === null || receiverUserId.length === 0 || receiverUserId.length > 64) {
    throw new AppError("INVALID_BODY");
  }
  return { receiverUserId };
}

export function parseUserIdQuery(raw: string | undefined): string {
  if (raw === undefined || raw.length === 0 || raw.length > 64) {
    throw new AppError("INVALID_BODY");
  }
  return raw;
}

export function parseDeviceKeyBody(body: unknown): DeviceKeyInput {
  if (!isObject(body)) throw new AppError("INVALID_BODY");
  const deviceId = requireString(body, "device_id");
  const platform = requireString(body, "platform");
  const deviceName = requireString(body, "device_name");
  const ed25519Pub = parsePublicKey(requireString(body, "ed25519_pub_b64"));
  const x25519Pub = parsePublicKey(requireString(body, "x25519_pub_b64"));
  const rawVersion = body["app_version"];

  if (!isUlid(deviceId)) throw new AppError("INVALID_BODY");
  if (!platform || !["ios", "android", "macos", "windows"].includes(platform)) {
    throw new AppError("INVALID_BODY");
  }
  if (!deviceName || deviceName.trim().length === 0 || deviceName.trim().length > 64) {
    throw new AppError("INVALID_BODY");
  }
  if (rawVersion !== undefined && rawVersion !== null && typeof rawVersion !== "string") {
    throw new AppError("INVALID_BODY");
  }
  const appVersion = typeof rawVersion === "string" && rawVersion.trim().length > 0
    ? rawVersion.trim().slice(0, 64)
    : null;

  return {
    deviceId,
    platform,
    deviceName: deviceName.trim(),
    ed25519Pub,
    x25519Pub,
    appVersion,
  };
}

export function parseTransferSessionBody(body: unknown): TransferSessionInput {
  if (!isObject(body)) throw new AppError("INVALID_BODY");
  const receiverUserId = requireString(body, "receiver_user_id");
  const receiverDeviceId = requireString(body, "receiver_device_id");
  const transferId = requireString(body, "transfer_id");
  const manifestHashB64 = requireString(body, "manifest_hash_b64");
  const senderX25519EphPubB64 = requireString(body, "sender_x25519_eph_pub_b64");
  const senderDeviceId = requireString(body, "sender_device_id");
  const senderInviteSignatureB64 = requireString(body, "sender_invite_signature_b64");

  if (!receiverUserId || receiverUserId.length > 64) throw new AppError("INVALID_BODY");
  if (!isUlid(receiverDeviceId)) throw new AppError("INVALID_BODY");
  if (!transferId || transferId.length > 128) throw new AppError("INVALID_BODY");
  if (manifestHashB64 === null || senderX25519EphPubB64 === null || senderInviteSignatureB64 === null) {
    throw new AppError("INVALID_PUBLIC_KEY");
  }
  parsePublicKey(manifestHashB64);
  parsePublicKey(senderX25519EphPubB64);
  parseBase64Bytes(senderInviteSignatureB64, 64);
  if (senderDeviceId !== null && !isUlid(senderDeviceId)) throw new AppError("INVALID_BODY");

  return {
    receiverUserId,
    receiverDeviceId,
    transferId,
    manifestHashB64,
    senderX25519EphPubB64,
    senderDeviceId,
    senderInviteSignatureB64,
  };
}

function isUlid(value: string | null): value is string {
  return value !== null && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(value);
}

function parsePublicKey(value: string | null): Uint8Array {
  return parseBase64Bytes(value, 32);
}

function parseBase64Bytes(value: string | null, expectedLength: number): Uint8Array {
  if (value === null) throw new AppError("INVALID_PUBLIC_KEY");
  try {
    const binary = atob(value);
    if (binary.length !== expectedLength) throw new AppError("INVALID_PUBLIC_KEY");
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch (err) {
    if (err instanceof AppError) throw err;
    throw new AppError("INVALID_PUBLIC_KEY");
  }
}
