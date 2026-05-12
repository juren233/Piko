import { defineWorkersProject } from "@cloudflare/vitest-pool-workers/config";
import { testAccountMigrations } from "./test/helpers/accountMigrations";

export default defineWorkersProject(() => {
  return {
    test: {
      setupFiles: ["./test/helpers/applyMigrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          isolatedStorage: true,
          miniflare: {
            compatibilityDate: "2024-11-01",
            compatibilityFlags: ["nodejs_compat"],
            d1Databases: ["DB"],
            kvNamespaces: ["SESSIONS"],
            bindings: {
              TEST_MIGRATIONS: testAccountMigrations,
            },
          },
        },
      },
    },
  };
});
