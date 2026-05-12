import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError, appErrorResponse } from "../errors.js";
import { parseLogin } from "../validation/schemas.js";
import { findUserByEmailNormalized } from "../db/users.js";
import { PBKDF2_ITER, verifyPassword } from "../auth/passwords.js";
import { createSession } from "../auth/session.js";

export const loginRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

loginRoute.post("/", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return appErrorResponse(c, new AppError("INVALID_CREDENTIALS"));
  }

  let input;
  try {
    input = parseLogin(body);
  } catch (err) {
    if (err instanceof AppError) return appErrorResponse(c, err);
    throw err;
  }

  const row = await findUserByEmailNormalized(c.env, input.emailNormalized);
  // 不区分用户不存在 vs 密码错（防枚举）；即使没用户也要走一次 verifyPassword 抗时序侧信道
  const dummy = JSON.stringify({
    algo: "pbkdf2-sha256",
    iter: PBKDF2_ITER,
    salt: "AAAA",
    hash: "AAAA",
  });
  const ok = await verifyPassword(input.password, row?.password_hash ?? dummy);
  if (!row || !ok) {
    return appErrorResponse(c, new AppError("INVALID_CREDENTIALS"));
  }

  const token = await createSession(c.env, row.id);

  return c.json({
    token,
    user: {
      id: row.id,
      email: row.email,
      username: row.username,
      nickname: row.nickname,
    },
  });
});
