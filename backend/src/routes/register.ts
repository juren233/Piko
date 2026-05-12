import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError, appErrorResponse } from "../errors.js";
import { parseRegister } from "../validation/schemas.js";
import {
  findUserByEmailNormalized,
  findUserByUsernameNormalized,
  insertUser,
} from "../db/users.js";
import { hashPassword } from "../auth/passwords.js";
import { createSession } from "../auth/session.js";
import { newId } from "../db/ids.js";

export const registerRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

registerRoute.post("/", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return appErrorResponse(c, new AppError("INVALID_BODY"));
  }

  let input;
  try {
    input = parseRegister(body);
  } catch (err) {
    if (err instanceof AppError) return appErrorResponse(c, err);
    throw err;
  }

  if (await findUserByEmailNormalized(c.env, input.emailNormalized)) {
    return appErrorResponse(c, new AppError("EMAIL_TAKEN"));
  }
  if (await findUserByUsernameNormalized(c.env, input.usernameNormalized)) {
    return appErrorResponse(c, new AppError("USERNAME_TAKEN"));
  }

  const id = newId();
  const passwordHash = await hashPassword(input.password);
  const createdAt = Math.floor(Date.now() / 1000);

  try {
    await insertUser(c.env, {
      id,
      email: input.email,
      emailNormalized: input.emailNormalized,
      username: input.username,
      usernameNormalized: input.usernameNormalized,
      nickname: input.nickname,
      passwordHash,
      createdAt,
    });
  } catch (err) {
    // 极小概率：并发注册撞上唯一索引（前面的预检过了但 INSERT 时冲突）
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes("UNIQUE") && message.includes("email_normalized")) {
      return appErrorResponse(c, new AppError("EMAIL_TAKEN"));
    }
    if (message.includes("UNIQUE") && message.includes("username_normalized")) {
      return appErrorResponse(c, new AppError("USERNAME_TAKEN"));
    }
    throw err;
  }

  const token = await createSession(c.env, id);

  return c.json(
    {
      token,
      user: {
        id,
        email: input.email,
        username: input.username,
        nickname: input.nickname,
      },
    },
    201,
  );
});
