import { Hono } from "hono";
import type { AppVariables, Env } from "./env.js";
import { AppError, appErrorResponse } from "./errors.js";
import { registerRoute } from "./routes/register.js";
import { loginRoute } from "./routes/login.js";
import { logoutRoute } from "./routes/logout.js";
import { meRoute } from "./routes/me.js";

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

export default app;
