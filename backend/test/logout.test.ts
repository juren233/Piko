import { describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface ErrEnvelope {
  error: { code: string; message: string };
}

async function newAccount(username: string) {
  const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
    body: {
      email: `${username}@example.com`,
      password: "hunter2hunter2",
      username,
    },
  });
  return res.json.token;
}

describe("POST /v1/auth/logout", () => {
  it("logout invalidates token (subsequent /me returns 401)", async () => {
    const token = await newAccount("logoutuser1");

    const me1 = await call<AuthSuccess>("GET", "/v1/users/me", { bearer: token });
    expect(me1.status).toBe(200);

    const out = await call("POST", "/v1/auth/logout", { bearer: token });
    expect(out.status).toBe(204);

    const me2 = await call<ErrEnvelope>("GET", "/v1/users/me", { bearer: token });
    expect(me2.status).toBe(401);
    expect(me2.json.error.code).toBe("SESSION_EXPIRED");
  });

  it("returns 204 even without Authorization header", async () => {
    const out = await call("POST", "/v1/auth/logout");
    expect(out.status).toBe(204);
  });

  it("returns 204 on bogus token (idempotent)", async () => {
    const out = await call("POST", "/v1/auth/logout", {
      bearer: "not-a-real-token-but-43-chars-long-padding!!",
    });
    expect(out.status).toBe(204);
  });
});
