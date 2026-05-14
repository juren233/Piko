import { describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import app from "../src/index.js";
import { SignalingHub } from "../src/do/SignalingHub.js";
import type { Env } from "../src/env.js";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}

interface ErrEnvelope {
  error: { code: string; message: string };
}

interface DeviceEnvelope {
  device: DeviceItem;
}

interface DevicesEnvelope {
  devices: DeviceItem[];
}

interface FriendsEnvelope {
  friends: Array<{
    user_id: string;
    online: boolean;
    last_seen_at: number | null;
  }>;
}

interface IceConfigEnvelope {
  ice_servers: IceServerItem[];
  ttl_seconds: number;
}

interface SessionEnvelope {
  session_id: string;
  ice_servers: IceServerItem[];
  expires_at: number;
}

interface IceServerItem {
  urls: string;
}

interface DeviceItem {
  device_id: string;
  platform: string;
  device_name: string;
  ed25519_pub_b64: string;
  x25519_pub_b64: string;
  last_seen_at: number | null;
  online: boolean;
}

const KEY_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
const KEY_B = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
const SIGNATURE_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

async function register(label: string): Promise<AuthSuccess> {
  const res = await call<AuthSuccess>("POST", "/v1/auth/register", {
    body: {
      email: `${label}@example.com`,
      password: "hunter2hunter2",
      username: label,
      nickname: label.toUpperCase(),
    },
  });
  expect(res.status).toBe(201);
  return res.json;
}

async function makeFriends(a: AuthSuccess, b: AuthSuccess): Promise<void> {
  const request = await call<{ request: { id: string } }>("POST", "/v1/friends/requests", {
    bearer: a.token,
    body: { receiver_user_id: b.user.id },
  });
  expect(request.status).toBe(201);

  const accepted = await call("POST", `/v1/friends/requests/${request.json.request.id}/accept`, {
    bearer: b.token,
  });
  expect(accepted.status).toBe(200);
}

async function registerDevice(
  auth: AuthSuccess,
  deviceId: string,
  deviceName: string,
): Promise<DeviceEnvelope> {
  const res = await call<DeviceEnvelope>("POST", "/v1/devices/keys", {
    bearer: auth.token,
    body: {
      device_id: deviceId,
      platform: "ios",
      device_name: deviceName,
      ed25519_pub_b64: KEY_A,
      x25519_pub_b64: KEY_B,
      app_version: "1.1.0-test",
    },
  });
  expect(res.status).toBe(201);
  return res.json;
}

class RecordingSocket {
  readonly sent: Array<Record<string, unknown>> = [];
  tags: string[] = [];
  private attachment: unknown = null;

  send(raw: string): void {
    this.sent.push(JSON.parse(raw) as Record<string, unknown>);
  }

  serializeAttachment(value: unknown): void {
    this.attachment = value;
  }

  deserializeAttachment(): unknown {
    return this.attachment;
  }
}

function attachDevice(ws: RecordingSocket, deviceId: string): RecordingSocket {
  ws.tags = [deviceId];
  ws.serializeAttachment({ device_id: deviceId });
  return ws;
}

function hubStateRecorder(webSockets: RecordingSocket[] = []): DurableObjectState & { alarmCalls: number } {
  const stored = new Map<string, unknown>();
  const state = {
    alarmCalls: 0,
    acceptWebSocket: (ws: WebSocket, tags: string[]) => {
      const recording = ws as unknown as RecordingSocket;
      recording.tags = tags;
      webSockets.push(recording);
    },
    getWebSockets: (tag?: string) =>
      (tag ? webSockets.filter((ws) => ws.tags.includes(tag)) : webSockets) as unknown as WebSocket[],
    storage: {
      setAlarm: () => {
        state.alarmCalls += 1;
        return Promise.resolve();
      },
      put: (key: string, value: unknown) => {
        stored.set(key, value);
        return Promise.resolve();
      },
      get: <T = unknown>(key: string) => Promise.resolve((stored.get(key) as T | undefined) ?? null),
      delete: (key: string) => {
        stored.delete(key);
        return Promise.resolve(false);
      },
      list: <T = unknown>(options?: { prefix?: string }) => {
        const prefix = options?.prefix ?? "";
        const entries = [...stored.entries()].filter(([key]) => key.startsWith(prefix)) as Array<[string, T]>;
        return Promise.resolve(new Map(entries));
      },
    },
  };
  return state as unknown as DurableObjectState & { alarmCalls: number };
}

function signalingNamespace(hubs: Map<string, SignalingHub>) {
  return {
    idFromName(name: string) {
      return { name };
    },
    get(id: { name: string }) {
      return {
        fetch: (request: Request | string, init?: RequestInit) => {
          const hub = hubs.get(id.name);
          if (!hub) throw new Error(`missing hub ${id.name}`);
          const req = typeof request === "string" ? new Request(request, init) : request;
          return hub.fetch(req);
        },
      };
    },
  };
}

async function appCall<T = unknown>(
  testEnv: Env,
  method: "DELETE" | "GET" | "POST",
  path: string,
  options: { body?: unknown; bearer?: string } = {},
): Promise<{ status: number; json: T }> {
  const headers: Record<string, string> = {};
  let body: string | undefined;
  if (options.body !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  if (options.bearer) {
    headers.authorization = `Bearer ${options.bearer}`;
  }
  const res = await app.request(`https://piko.test${path}`, { method, headers, body }, testEnv);
  let json: T;
  if (res.status === 204) {
    json = undefined as T;
  } else {
    json = (await res.json()) as T;
  }
  return { status: res.status, json };
}

function transferSessionBody(receiverUserId: string, receiverDeviceId: string, senderDeviceId?: string) {
  return {
    receiver_user_id: receiverUserId,
    receiver_device_id: receiverDeviceId,
    transfer_id: "transfer-001",
    manifest_hash_b64: KEY_A,
    sender_x25519_eph_pub_b64: KEY_B,
    sender_device_id: senderDeviceId,
    sender_invite_signature_b64: SIGNATURE_A,
  };
}

describe("cross-network signaling control plane", () => {
  it("returns Cloudflare STUN endpoints from ice config", async () => {
    const alice = await register("iceconfigalice");

    const res = await call<IceConfigEnvelope>("GET", "/v1/ice-config", { bearer: alice.token });

    expect(res.status).toBe(200);
    expect(res.json.ice_servers).toEqual([
      { urls: "stun:stun.cloudflare.com:3478" },
      { urls: "stun:stun.cloudflare.com:53" },
    ]);
    expect(res.json.ice_servers.map((server) => server.urls)).not.toContain(
      "stun:stun.l.google.com:19302",
    );
    expect(res.json.ttl_seconds).toBe(3600);
  });

  it("upserts device public keys and exposes friend device status", async () => {
    const alice = await register("devicekeysalice");
    const bob = await register("devicekeysbob");
    await makeFriends(alice, bob);

    const created = await registerDevice(bob, "01ARZ3NDEKTSV4RRFFQ69G5FAV", "Bob Phone");
    expect(created.device).toMatchObject({
      device_id: "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      device_name: "Bob Phone",
      online: false,
    });

    const updated = await registerDevice(bob, "01ARZ3NDEKTSV4RRFFQ69G5FAV", "Bob iPhone");
    expect(updated.device.device_name).toBe("Bob iPhone");

    const devices = await call<DevicesEnvelope>(`GET`, `/v1/friends/${bob.user.id}/devices`, {
      bearer: alice.token,
    });
    expect(devices.status).toBe(200);
    expect(devices.json.devices).toMatchObject([
      {
        device_id: "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        device_name: "Bob iPhone",
        online: false,
      },
    ]);
  });

  it("rejects non-friend device key lookup and malformed public keys", async () => {
    const alice = await register("deviceguardalice");
    const bob = await register("deviceguardbob");

    const malformed = await call<ErrEnvelope>("POST", "/v1/devices/keys", {
      bearer: bob.token,
      body: {
        device_id: "01ARZ3NDEKTSV4RRFFQ69G5FAW",
        platform: "android",
        device_name: "Bad Key",
        ed25519_pub_b64: "bad",
        x25519_pub_b64: KEY_B,
      },
    });
    expect(malformed.status).toBe(400);
    expect(malformed.json.error.code).toBe("INVALID_PUBLIC_KEY");

    await registerDevice(bob, "01ARZ3NDEKTSV4RRFFQ69G5FAX", "Bob Tablet");

    const forbidden = await call<ErrEnvelope>("GET", `/v1/devices/keys?user_id=${bob.user.id}`, {
      bearer: alice.token,
    });
    expect(forbidden.status).toBe(403);
    expect(forbidden.json.error.code).toBe("TRANSFER_SESSION_FORBIDDEN");
  });

  it("guards transfer session creation by friendship and invite delivery", async () => {
    const alice = await register("sessionguardalice");
    const bob = await register("sessionguardbob");
    await registerDevice(alice, "01ARZ3NDEKTSV4RRFFQ69G5FAZ", "Alice Phone");
    await registerDevice(bob, "01ARZ3NDEKTSV4RRFFQ69G5FAY", "Bob Offline");

    const nonFriend = await call<ErrEnvelope>("POST", "/v1/transfers/sessions", {
      bearer: alice.token,
      body: transferSessionBody(bob.user.id, "01ARZ3NDEKTSV4RRFFQ69G5FAY", "01ARZ3NDEKTSV4RRFFQ69G5FAZ"),
    });
    expect(nonFriend.status).toBe(403);
    expect(nonFriend.json.error.code).toBe("TRANSFER_PEER_NOT_FRIEND");

    await makeFriends(alice, bob);
    const hubs = new Map<string, SignalingHub>();
    const senderState = hubStateRecorder();
    const receiverState = hubStateRecorder();
    const testEnv = {
      ...env,
      SIGNALING_HUB: signalingNamespace(hubs),
    };
    hubs.set(alice.user.id, new SignalingHub(senderState, testEnv as unknown as Env));
    hubs.set(bob.user.id, new SignalingHub(receiverState, testEnv as unknown as Env));

    const offline = await appCall<ErrEnvelope>(testEnv as unknown as Env, "POST", "/v1/transfers/sessions", {
      bearer: alice.token,
      body: transferSessionBody(bob.user.id, "01ARZ3NDEKTSV4RRFFQ69G5FAY", "01ARZ3NDEKTSV4RRFFQ69G5FAZ"),
    });
    expect(offline.status).toBe(409);
    expect(offline.json.error.code).toBe("DEVICE_OFFLINE");
    expect((await receiverState.storage.list({ prefix: "sr:" })).size).toBe(0);
  });

  it("reports friend online from active signaling websocket without writing device TTL", async () => {
    const alice = await register("devicepresencealice");
    const bob = await register("devicepresencebob");
    await makeFriends(alice, bob);
    await registerDevice(bob, "01HR0A9S9Y1N2Z3X4W5V6T7S9A", "Bob Phone");
    const bobWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S9A");
    const hubs = new Map<string, SignalingHub>();
    const testEnv = {
      ...env,
      SIGNALING_HUB: signalingNamespace(hubs),
    };
    const bobState = hubStateRecorder([bobWs]);
    hubs.set(alice.user.id, new SignalingHub(hubStateRecorder(), testEnv as unknown as Env));
    hubs.set(bob.user.id, new SignalingHub(bobState, testEnv as unknown as Env));

    const friends = await appCall<FriendsEnvelope>(testEnv as unknown as Env, "GET", "/v1/friends", { bearer: alice.token });

    expect(friends.status).toBe(200);
    expect(friends.json.friends[0]).toMatchObject({
      user_id: bob.user.id,
      online: true,
    });
    expect(await env.SESSIONS.get("dp:01HR0A9S9Y1N2Z3X4W5V6T7S9A")).toBe(null);
  });

  it("accepts signaling websockets without scheduling alarms or writing device presence", async () => {
    const state = hubStateRecorder();
    const hub = new SignalingHub(state, env as unknown as Env);

    const res = await hub.fetch(new Request("https://signaling.local/ws?device_id=01HR0A9S9Y1N2Z3X4W5V6T7S9B", {
      headers: { upgrade: "websocket" },
    }));

    expect(res.status).toBe(101);
    expect(state.alarmCalls).toBe(0);
    expect(await env.SESSIONS.get("dp:01HR0A9S9Y1N2Z3X4W5V6T7S9B")).toBe(null);
  });

  it("routes signaling websocket to the authenticated user's durable object shard", async () => {
    const alice = await register("signalroutealice");
    await registerDevice(alice, "01HR0A9S9Y1N2Z3X4W5V6T7S8R", "Alice Phone");

    const shardNames: string[] = [];
    const testEnv = {
      ...env,
      SIGNALING_HUB: {
        idFromName(name: string) {
          shardNames.push(name);
          return { name };
        },
        get() {
          return { fetch: () => Response.json({ ok: true }) };
        },
      },
    };

    const res = await app.request(
      "https://piko.test/v1/signaling/ws?device_id=01HR0A9S9Y1N2Z3X4W5V6T7S8R",
      { headers: { authorization: `Bearer ${alice.token}` } },
      testEnv,
    );

    expect(res.status).toBe(200);
    expect(shardNames).toEqual([alice.user.id]);
  });

  it("routes invite and peer signaling across user-sharded durable objects", async () => {
    const alice = await register("signalhubalice");
    const bob = await register("signalhubbob");
    await makeFriends(alice, bob);
    await registerDevice(alice, "01HR0A9S9Y1N2Z3X4W5V6T7S8R", "Alice Phone");
    await registerDevice(bob, "01HR0A9S9Y1N2Z3X4W5V6T7S8S", "Bob Phone");

    const senderWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S8R");
    const receiverWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S8S");
    const senderState = hubStateRecorder([senderWs]);
    const receiverState = hubStateRecorder([receiverWs]);
    const hubs = new Map<string, SignalingHub>();
    const testEnv = {
      ...env,
      SIGNALING_HUB: signalingNamespace(hubs),
    };
    let senderHub = new SignalingHub(senderState, testEnv as unknown as Env);
    const receiverHub = new SignalingHub(receiverState, testEnv as unknown as Env);
    hubs.set(alice.user.id, senderHub);
    hubs.set(bob.user.id, receiverHub);

    const created = await appCall<SessionEnvelope>(testEnv as unknown as Env, "POST", "/v1/transfers/sessions", {
      bearer: alice.token,
      body: transferSessionBody(bob.user.id, "01HR0A9S9Y1N2Z3X4W5V6T7S8S", "01HR0A9S9Y1N2Z3X4W5V6T7S8R"),
    });
    expect(created.status).toBe(201);
    expect(created.json.ice_servers).toEqual([
      { urls: "stun:stun.cloudflare.com:3478" },
      { urls: "stun:stun.cloudflare.com:53" },
    ]);
    expect(receiverWs.sent.at(-1)).toMatchObject({
      type: "invite",
      session_id: created.json.session_id,
      from_device_id: "01HR0A9S9Y1N2Z3X4W5V6T7S8R",
      from_user_id: alice.user.id,
      transfer_id: "transfer-001",
      manifest_hash_b64: KEY_A,
      sender_x25519_eph_pub_b64: KEY_B,
      sender_invite_signature_b64: SIGNATURE_A,
      sender_ed25519_pub_b64: KEY_A,
      sender_x25519_pub_b64: KEY_B,
      ice_servers: created.json.ice_servers,
      same_account: false,
    });

    senderHub = new SignalingHub(senderState, testEnv as unknown as Env);
    hubs.set(alice.user.id, senderHub);
    await receiverHub.webSocketMessage(
      receiverWs as unknown as WebSocket,
      JSON.stringify({ type: "answer", session_id: created.json.session_id, sdp: "answer-sdp" }),
    );
    expect(senderWs.sent.at(-1)).toMatchObject({
      type: "answer",
      session_id: created.json.session_id,
      sdp: "answer-sdp",
    });

    senderHub = new SignalingHub(senderState, testEnv as unknown as Env);
    hubs.set(alice.user.id, senderHub);
    await senderHub.webSocketMessage(
      senderWs as unknown as WebSocket,
      JSON.stringify({
        type: "ice_candidate",
        session_id: created.json.session_id,
        candidate: "candidate:1 1 udp 2122260223 192.0.2.1 54400 typ host",
        sdp_mid: "data",
        sdp_m_line_index: 0,
      }),
    );
    expect(receiverWs.sent.at(-1)).toMatchObject({
      type: "ice_candidate",
      session_id: created.json.session_id,
      sdp_mid: "data",
      sdp_m_line_index: 0,
    });

    await senderHub.fetch(
      new Request("https://signaling.local/closeSession", {
        method: "POST",
        body: JSON.stringify({ session_id: created.json.session_id, reason: "finished" }),
      }),
    );
    expect(senderWs.sent.at(-1)).toMatchObject({ type: "bye", session_id: created.json.session_id });
  });

  it("keeps signaling sessions alive when an older duplicate device websocket closes", async () => {
    const alice = await register("signaldupwsalice");
    const bob = await register("signaldupwsbob");
    await makeFriends(alice, bob);
    await registerDevice(alice, "01HR0A9S9Y1N2Z3X4W5V6T7S8T", "Alice Phone");
    await registerDevice(bob, "01HR0A9S9Y1N2Z3X4W5V6T7S8V", "Bob Phone");

    const senderWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S8T");
    const receiverOldWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S8V");
    const receiverNewWs = attachDevice(new RecordingSocket(), "01HR0A9S9Y1N2Z3X4W5V6T7S8V");
    const senderState = hubStateRecorder([senderWs]);
    const receiverState = hubStateRecorder([receiverOldWs, receiverNewWs]);
    const hubs = new Map<string, SignalingHub>();
    const testEnv = {
      ...env,
      SIGNALING_HUB: signalingNamespace(hubs),
    };
    const senderHub = new SignalingHub(senderState, testEnv as unknown as Env);
    const receiverHub = new SignalingHub(receiverState, testEnv as unknown as Env);
    hubs.set(alice.user.id, senderHub);
    hubs.set(bob.user.id, receiverHub);

    const created = await appCall<SessionEnvelope>(testEnv as unknown as Env, "POST", "/v1/transfers/sessions", {
      bearer: alice.token,
      body: transferSessionBody(bob.user.id, "01HR0A9S9Y1N2Z3X4W5V6T7S8V", "01HR0A9S9Y1N2Z3X4W5V6T7S8T"),
    });
    expect(created.status).toBe(201);

    await receiverHub.webSocketClose(receiverOldWs as unknown as WebSocket);
    expect(senderWs.sent.at(-1)).not.toMatchObject({
      type: "bye",
      session_id: created.json.session_id,
      reason: "device_closed",
    });

    await receiverHub.webSocketMessage(
      receiverNewWs as unknown as WebSocket,
      JSON.stringify({ type: "answer", session_id: created.json.session_id, sdp: "answer-after-reconnect" }),
    );
    expect(senderWs.sent.at(-1)).toMatchObject({
      type: "answer",
      session_id: created.json.session_id,
      sdp: "answer-after-reconnect",
    });
  });
});
