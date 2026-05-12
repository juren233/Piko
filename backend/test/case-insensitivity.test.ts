import { describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface ErrEnvelope {
  error: { code: string; message: string };
}

describe("email + username case normalization", () => {
  it("registers Mixed@Case.com, logs in as mixed@case.com", async () => {
    const reg = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: {
        email: "Mixed@Case.com",
        password: "hunter2hunter2",
        username: "MixedUser",
      },
    });
    expect(reg.status).toBe(201);

    const login = await call<AuthSuccess>("POST", "/v1/auth/login", {
      body: { email: "  mixed@case.com  ", password: "hunter2hunter2" },
    });
    expect(login.status).toBe(200);
    expect(login.json.user.id).toBe(reg.json.user.id);
    // 显示原值保留大小写
    expect(login.json.user.email).toBe("Mixed@Case.com");
    expect(login.json.user.username).toBe("MixedUser");
  });

  it("rejects re-registration of same email in different case", async () => {
    await call("POST", "/v1/auth/register", {
      body: { email: "Same@Case.com", password: "hunter2hunter2", username: "samecase1" },
    });
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "SAME@case.COM", password: "hunter2hunter2", username: "samecase2" },
    });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("EMAIL_TAKEN");
  });

  it("rejects re-registration of same username in different case", async () => {
    await call("POST", "/v1/auth/register", {
      body: { email: "uniqemail1@example.com", password: "hunter2hunter2", username: "UniqueName" },
    });
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "uniqemail2@example.com", password: "hunter2hunter2", username: "uniquename" },
    });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("USERNAME_TAKEN");
  });
});
