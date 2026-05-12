import { describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}
interface ErrEnvelope {
  error: { code: string; message: string };
}

describe("POST /v1/auth/register", () => {
  it("happy path: 201 + token + user", async () => {
    const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: {
        email: "alice@example.com",
        password: "hunter2hunter2",
        username: "alice",
        nickname: "Alice",
      },
    });
    expect(res.status).toBe(201);
    expect(typeof res.json.token).toBe("string");
    expect(res.json.token.length).toBe(43);
    expect(res.json.user.email).toBe("alice@example.com");
    expect(res.json.user.username).toBe("alice");
    expect(res.json.user.nickname).toBe("Alice");
    expect(typeof res.json.user.id).toBe("string");
  });

  it("accepts missing nickname", async () => {
    const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: { email: "bob@example.com", password: "hunter2hunter2", username: "bob" },
    });
    expect(res.status).toBe(201);
    expect(res.json.user.nickname).toBe(null);
  });

  it("treats empty-string nickname as null", async () => {
    const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: {
        email: "carol@example.com",
        password: "hunter2hunter2",
        username: "carol",
        nickname: "   ",
      },
    });
    expect(res.status).toBe(201);
    expect(res.json.user.nickname).toBe(null);
  });

  it("rejects duplicate email with 409 EMAIL_TAKEN", async () => {
    await call("POST", "/v1/auth/register", {
      body: { email: "dave@example.com", password: "hunter2hunter2", username: "dave1" },
    });
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "dave@example.com", password: "hunter2hunter2", username: "dave2" },
    });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("EMAIL_TAKEN");
  });

  it("rejects duplicate username with 409 USERNAME_TAKEN", async () => {
    await call("POST", "/v1/auth/register", {
      body: { email: "eve1@example.com", password: "hunter2hunter2", username: "eve" },
    });
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "eve2@example.com", password: "hunter2hunter2", username: "eve" },
    });
    expect(res.status).toBe(409);
    expect(res.json.error.code).toBe("USERNAME_TAKEN");
  });

  it("rejects short password with 400 INVALID_PASSWORD", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "frank@example.com", password: "short", username: "frank" },
    });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_PASSWORD");
  });

  it("rejects bad email with 400 INVALID_EMAIL", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "no-at-sign", password: "hunter2hunter2", username: "grace" },
    });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_EMAIL");
  });

  it("rejects username containing space with 400 INVALID_USERNAME", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "henry@example.com", password: "hunter2hunter2", username: "henry x" },
    });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_USERNAME");
  });

  it("rejects username shorter than 3 with 400 INVALID_USERNAME", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {
      body: { email: "ian@example.com", password: "hunter2hunter2", username: "ab" },
    });
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_USERNAME");
  });

  it("accepts CJK username (NFKC + lowercase normalize)", async () => {
    const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
      body: {
        email: "jade@example.com",
        password: "hunter2hunter2",
        username: "赤色星河",
        nickname: "Jade",
      },
    });
    expect(res.status).toBe(201);
    expect(res.json.user.username).toBe("赤色星河");
  });

  it("rejects malformed JSON body", async () => {
    const res = await call<ErrEnvelope>("POST", "/v1/auth/register", {});
    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_BODY");
  });
});
