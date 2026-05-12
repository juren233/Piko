import { applyD1Migrations, env } from "cloudflare:test";
import { beforeAll } from "vitest";

beforeAll(async () => {
  // TEST_MIGRATIONS 由 vitest.config.ts 通过 readD1Migrations + miniflare bindings 注入
  // ProvidedEnv 在 test/env.d.ts 已被增强
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});
