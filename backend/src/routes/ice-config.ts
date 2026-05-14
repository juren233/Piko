import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";
import { p2pIceServers } from "../ice.js";

export const iceConfigRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

iceConfigRoute.use("*", requireAuth);

iceConfigRoute.get("/", (c) =>
  c.json({
    ice_servers: p2pIceServers(),
    ttl_seconds: 3600,
  }),
);
