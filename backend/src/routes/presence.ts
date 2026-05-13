import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";
import { touchPresence } from "../db/presence.js";

export const presenceRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

presenceRoute.use("*", requireAuth);

presenceRoute.post("/heartbeat", async (c) => {
  const currentUser = c.get("user");
  await touchPresence(c.env, currentUser.id, Math.floor(Date.now() / 1000));
  return new Response(null, { status: 204 });
});
