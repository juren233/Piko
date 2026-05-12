import { beforeAll, describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface ErrEnvelope {
  error: { code: string; message: string };
}

describe("POST /v1/auth/login", () => {
  beforeAll(async () => {
    await call("POST", "/v1/auth/register", {
      body: {
        email: "login-user@example.com",
        password: "hunter2hunter2",
        username: "loginuser",
        nickname: "Login User",
      },
    });
  });

  it("happy path: 200 + token + user", async () => {
    const res = await call<AuthSuccess>("POST", "/v1/auth/login", {
      body: { email: "login-user@example.com", password: "hunter2hunter2" },
    });
    expect(res.status).toBe(200);
    expect(typeof res.json.token).toBe("string");
    expect(res.json.user.username).toBe("loginuser");
  });

  it("issues a new token on each login", async () => {
    const first = await call<AuthSuccess>("POST", "/v1/auth/login", {
      body: { email: "login-user@example.com", password: "hunter2hunter2" },
    });
    const second = await call<AuthSuccess>("POST", "/v1/auth/login", {
      body: { email: "login-user@example.com", password: "hunter2hunter2" },
    });
    expect(first.json.token).not.toBe(second.json.token);
  });

  it("returns 401 INVALID_CREDENTIALS on wrong password", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/login", {
      body: { email: "login-user@example.com", password: "wrong-password-1" },
    });
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("INVALID_CREDENTIALS");
  });

  it("returns 401 INVALID_CREDENTIALS on non-existent email (no enumeration)", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/login", {
      body: { email: "ghost@example.com", password: "hunter2hunter2" },
    });
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("INVALID_CREDENTIALS");
  });

  it("returns 401 INVALID_CREDENTIALS on missing body fields", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/login", {
      body: { email: "login-user@example.com" },
    });
    expect(res.status).toBe(401);
    expect(res.json.error.code).toBe("INVALID_CREDENTIALS");
  });
});
