import { describe, expect, it } from "vitest";
import {
  hashPassword,
  verifyPassword,
  PBKDF2_ITER,
  PBKDF2_ALGO,
} from "../src/auth/passwords.js";

describe("password hashing", () => {
  it("hashes and verifies a password", async () => {
    const hash = await hashPassword("hunter2hunter2");
    const ok = await verifyPassword("hunter2hunter2", hash);
    expect(ok).toBe(true);
  });

  it("rejects wrong password", async () => {
    const hash = await hashPassword("hunter2hunter2");
    const ok = await verifyPassword("hunter2hunter3", hash);
    expect(ok).toBe(false);
  });

  it("rejects malformed phc string", async () => {
    const ok = await verifyPassword("anything", "not-json");
    expect(ok).toBe(false);
  });

  it("rejects record with unknown algorithm", async () => {
    const fakeRecord = JSON.stringify({
      algo: "bcrypt",
      iter: 12,
      salt: "AAAA",
      hash: "AAAA",
    });
    const ok = await verifyPassword("anything", fakeRecord);
    expect(ok).toBe(false);
  });

  it("uses 600,000 iterations and PBKDF2-SHA256", async () => {
    expect(PBKDF2_ITER).toBe(600_000);
    expect(PBKDF2_ALGO).toBe("pbkdf2-sha256");
    const hash = await hashPassword("any-password-ok");
    const record = JSON.parse(hash);
    expect(record.algo).toBe("pbkdf2-sha256");
    expect(record.iter).toBe(600_000);
    expect(typeof record.salt).toBe("string");
    expect(typeof record.hash).toBe("string");
  });

  it("produces different hashes for the same password (random salt)", async () => {
    const a = await hashPassword("hunter2hunter2");
    const b = await hashPassword("hunter2hunter2");
    expect(a).not.toBe(b);
  });
});
