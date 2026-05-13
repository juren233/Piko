import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";

export const iceConfigRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

iceConfigRoute.use("*", requireAuth);

iceConfigRoute.get("/", (c) =>
  c.json({
    ice_servers: [{ urls: "stun:stun.cloudflare.com:3478" }],
    ttl_seconds: 3600,
  }),
);
