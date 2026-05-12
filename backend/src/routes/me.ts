import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";

export const meRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

meRoute.get("/", requireAuth, (c) => {
  const user = c.get("user");
  return c.json({ user });
});
