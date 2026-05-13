import { Hono } from "hono";
import type { AppVariables, Env } from "./env.js";
import { AppError, appErrorResponse } from "./errors.js";
import { registerRoute } from "./routes/register.js";
import { loginRoute } from "./routes/login.js";
import { logoutRoute } from "./routes/logout.js";
import { meRoute } from "./routes/me.js";
import { usersSearchRoute } from "./routes/users-search.js";
import { friendRequestsRoute } from "./routes/friend-requests.js";
import { friendsRoute } from "./routes/friends.js";
import { presenceRoute } from "./routes/presence.js";
import { devicesRoute } from "./routes/devices.js";
import { iceConfigRoute } from "./routes/ice-config.js";
import { transferSessionsRoute } from "./routes/transfer-sessions.js";
import { signalingWsRoute } from "./routes/signaling-ws.js";
export { SignalingHub } from "./do/SignalingHub.js";

const app = new Hono<{ Bindings: Env; Variables: AppVariables }>();

app.onError((err, c) => {
  if (err instanceof AppError) {
    return appErrorResponse(c, err);
  }
  console.error("[piko-api] unhandled error:", err);
  return appErrorResponse(c, new AppError("INTERNAL"));
});

app.notFound((c) =>
  c.json({ error: { code: "NOT_FOUND", message: "路径不存在" } }, 404),
);

app.get("/", (c) =>
  c.json({ name: "piko-api", status: "ok", time: new Date().toISOString() }),
);

app.route("/v1/auth/register", registerRoute);
app.route("/v1/auth/login", loginRoute);
app.route("/v1/auth/logout", logoutRoute);
app.route("/v1/users/me", meRoute);
app.route("/v1/users/search", usersSearchRoute);
app.route("/v1/friends/requests", friendRequestsRoute);
app.route("/v1/friends", friendsRoute);
app.route("/v1/presence", presenceRoute);
app.route("/v1/devices", devicesRoute);
app.route("/v1/ice-config", iceConfigRoute);
app.route("/v1/transfers/sessions", transferSessionsRoute);
app.route("/v1/signaling", signalingWsRoute);

export default app;
