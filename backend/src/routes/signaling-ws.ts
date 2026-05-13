import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { AppError } from "../errors.js";
import { loadSession } from "../auth/session.js";
import { findActiveDevice } from "../db/devices.js";

export const signalingWsRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

signalingWsRoute.get("/ws", async (c) => {
  const deviceId = c.req.query("device_id");
  if (!deviceId) throw new AppError("DEVICE_NOT_FOUND");
  const auth = c.req.header("authorization");
  if (!auth?.startsWith("Bearer ")) throw new AppError("SESSION_EXPIRED");
  const token = auth.slice("Bearer ".length).trim();
  const session = await loadSession(c.env, token);
  if (!session) throw new AppError("SESSION_EXPIRED");

  const device = await findActiveDevice(c.env, deviceId);
  if (!device || device.user_id !== session.userId) {
    throw new AppError("DEVICE_NOT_OWNED");
  }

  if (!c.env.SIGNALING_HUB) throw new AppError("INTERNAL");
  const id = c.env.SIGNALING_HUB.idFromName(session.userId);
  return c.env.SIGNALING_HUB.get(id).fetch(c.req.raw);
});
