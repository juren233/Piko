// 增强 cloudflare:test 的 ProvidedEnv，让测试 helper 读取 vitest.config.ts 注入的绑定。
import type { Env } from "../src/env.js";
import type { D1Migration } from "cloudflare:test";

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {
    TEST_MIGRATIONS: D1Migration[];
  }
}
