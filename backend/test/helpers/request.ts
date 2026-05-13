import { env } from "cloudflare:test";
import app from "../../src/index.js";

const BASE = "https://piko.test";

export interface JsonResponse<T = unknown> {
  status: number;
  json: T;
  headers: Headers;
}

export async function call<T = unknown>(
  method: "DELETE" | "GET" | "POST",
  path: string,
  options: { body?: unknown; bearer?: string } = {},
): Promise<JsonResponse<T>> {
  const headers: Record<string, string> = {};
  let body: string | undefined;
  if (options.body !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  if (options.bearer) {
    headers["authorization"] = `Bearer ${options.bearer}`;
  }
  const res = await app.request(
    `${BASE}${path}`,
    { method, headers, body },
    env,
  );
  const status = res.status;
  let json: T;
  if (status === 204 || res.headers.get("content-length") === "0") {
    json = undefined as T;
  } else {
    json = (await res.json()) as T;
  }
  return { status, json, headers: res.headers };
}
