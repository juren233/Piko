// Augment cloudflare:test's ProvidedEnv so test helpers get our bindings + the
// auto-injected TEST_MIGRATIONS array (set up by vitest.config.ts).
import type { Env } from "../src/env.js";
import type { D1Migration } from "cloudflare:test";

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {
    TEST_MIGRATIONS: D1Migration[];
  }
}
