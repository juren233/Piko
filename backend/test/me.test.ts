import { describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface MeResponse {
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface ErrEnvelope {
  error: { code: string; message: string };
}

describe("GET /v1/users/me", () => {
  it("happy path returns user", async () => {
    const reg = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: {
        email: "me1@example.com",
        password: "hunter2hunter2",
        username: "me1user",
        nickname: "Me One",
      },
    });
    const res = await call<MeResponse>("GET", "/v1/users/me", { bearer: reg.json.token });
    expect(res.status).toBe(200);
    expect(res.json.user.username).toBe("me1user");
    expect(res.json.user.nickname).toBe("Me One");
    expect(res.json.user.email).toBe("me1@example.com");
  });

  it("returns 401 SESSION_EXPIRED without header", async () => {
    const res = await call<ErrEnvelope>("GET", "/v1/users/me");
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("SESSION_EXPIRED");
  });

  it("returns 401 SESSION_EXPIRED on malformed bearer header", async () => {
    const res = await call<ErrEnvelope>("GET", "/v1/users/me", { bearer: "" });
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("SESSION_EXPIRED");
  });

  it("returns 401 SESSION_EXPIRED on unknown token", async () => {
    const res = await call<ErrEnvelope>("GET", "/v1/users/me", {
      bearer: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    });
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("SESSION_EXPIRED");
  });
});
