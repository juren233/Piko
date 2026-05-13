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
