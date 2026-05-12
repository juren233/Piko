import type { Context, MiddlewareHandler } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError } from "../errors.js";
import { findUserById } from "../db/users.js";
import { loadSession } from "./session.js";

type AppContext = Context<{ Bindings: Env; Variables: AppVariables }>;

export const requireAuth: MiddlewareHandler<{ Bindings: Env; Variables: AppVariables }> = async (
  c: AppContext,
  next,
) => {
  const token = extractBearer(c.req.header("authorization"));
  if (!token) throw new AppError("SESSION_EXPIRED");

  const session = await loadSession(c.env, token);
  if (!session) throw new AppError("SESSION_EXPIRED");

  const row = await findUserById(c.env, session.userId);
  if (!row) throw new AppError("SESSION_EXPIRED");

  c.set("user", {
    id: row.id,
    email: row.email,
    username: row.username,
    nickname: row.nickname,
  });
  c.set("sessionToken", token);
  await next();
};

export function extractBearer(header: string | undefined): string | null {
  if (!header) return null;
  const m = header.match(/^Bearer\s+(.+)$/i);
  if (!m) return null;
  return m[1]!.trim();
}
