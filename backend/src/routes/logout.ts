import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { extractBearer } from "../auth/middleware.js";
import { revokeSession } from "../auth/session.js";

export const logoutRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

logoutRoute.post("/", async (c) => {
  const token = extractBearer(c.req.header("authorization"));
  if (token) {
    await revokeSession(c.env, token);
  }
  return c.body(null, 204);
});
