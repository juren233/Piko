import type { Env } from "../env.js";
import { touchDevicePresence } from "../db/presence.js";

interface SessionRoute {
  sender_user_id: string;
  sender_device_id: string;
  receiver_user_id: string;
  receiver_device_id: string;
  peer_user_id: string;
  expires_at: number;
}

interface InviteDispatch {
  session_id: string;
  from_device_id?: string;
  from_user_id: string;
  receiver_user_id: string;
  receiver_device_id: string;
  transfer_id: string;
  manifest_hash_b64: string;
  sender_x25519_eph_pub_b64: string;
  sender_invite_signature_b64: string;
  sender_ed25519_pub_b64: string;
  sender_x25519_pub_b64: string;
  same_account: boolean;
  expires_at: number;
}

interface SessionRegistration {
  session_id: string;
  sender_user_id: string;
  sender_device_id: string;
  receiver_user_id: string;
  receiver_device_id: string;
  peer_user_id: string;
  expires_at: number;
}

interface PeerDispatch {
  target_device_id: string;
  message: unknown;
}

interface SessionClose {
  session_id: string;
  reason?: string;
}

export class SignalingHub {
  private readonly liveWs = new Map<string, WebSocket>();
  private readonly sessions = new Map<string, SessionRoute>();

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/dispatchInvite" && request.method === "POST") {
      const payload = (await request.json()) as InviteDispatch;
      this.registerSessionRoute({
        session_id: payload.session_id,
        sender_user_id: payload.from_user_id,
        sender_device_id: payload.from_device_id ?? "",
        receiver_user_id: payload.receiver_user_id,
        receiver_device_id: payload.receiver_device_id,
        peer_user_id: payload.from_user_id,
        expires_at: payload.expires_at,
      });
      const delivered = this.sendToDevice(payload.receiver_device_id, {
        type: "invite",
        session_id: payload.session_id,
        from_device_id: payload.from_device_id ?? "",
        from_user_id: payload.from_user_id,
        transfer_id: payload.transfer_id,
        manifest_hash_b64: payload.manifest_hash_b64,
        sender_x25519_eph_pub_b64: payload.sender_x25519_eph_pub_b64,
        sender_invite_signature_b64: payload.sender_invite_signature_b64,
        sender_ed25519_pub_b64: payload.sender_ed25519_pub_b64,
        sender_x25519_pub_b64: payload.sender_x25519_pub_b64,
        same_account: payload.same_account,
      });
      return Response.json({ ok: true, delivered });
    }

    if (url.pathname === "/registerSession" && request.method === "POST") {
      this.registerSessionRoute((await request.json()) as SessionRegistration);
      return Response.json({ ok: true });
    }

    if (url.pathname === "/dispatchPeerMessage" && request.method === "POST") {
      const payload = (await request.json()) as PeerDispatch;
      const delivered = this.sendToDevice(payload.target_device_id, payload.message);
      return Response.json({ ok: true, delivered });
    }

    if (url.pathname === "/closeSession" && request.method === "POST") {
      const payload = (await request.json()) as SessionClose;
      this.closeSession(payload.session_id, payload.reason ?? "finished");
      return Response.json({ ok: true });
    }

    if (request.headers.get("upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    const urlDeviceId = url.searchParams.get("device_id");
    if (!urlDeviceId) return new Response("Missing device_id", { status: 400 });

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.acceptWebSocket(server, [urlDeviceId]);
    this.liveWs.set(urlDeviceId, server);
    await touchDevicePresence(this.env, urlDeviceId, Date.now());

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const deviceId = this.findDeviceId(ws);
    if (!deviceId || typeof raw !== "string") return;

    let message: { type?: string; session_id?: string; [key: string]: unknown };
    try {
      message = JSON.parse(raw) as { type?: string; session_id?: string; [key: string]: unknown };
    } catch {
      this.send(ws, { type: "error", code: "INVALID_BODY", message: "信令消息格式不合法" });
      return;
    }

    if (message.type === "hello") {
      await touchDevicePresence(this.env, deviceId, Date.now());
      this.send(ws, { type: "hello", device_id: deviceId });
      return;
    }

    if (message.type === "pong") {
      await touchDevicePresence(this.env, deviceId, Date.now());
      return;
    }

    const sessionId = typeof message.session_id === "string" ? message.session_id : null;
    if (!sessionId) {
      this.send(ws, { type: "error", code: "TRANSFER_SESSION_NOT_FOUND", message: "传输会话不存在" });
      return;
    }
    const route = this.sessions.get(sessionId);
    if (!route) {
      this.send(ws, { type: "error", session_id: sessionId, code: "TRANSFER_SESSION_NOT_FOUND", message: "传输会话不存在" });
      return;
    }
    if (deviceId !== route.sender_device_id && deviceId !== route.receiver_device_id) {
      this.send(ws, { type: "error", session_id: sessionId, code: "TRANSFER_SESSION_FORBIDDEN", message: "无权操作该传输会话" });
      return;
    }

    const peerDeviceId = deviceId === route.receiver_device_id ? route.sender_device_id : route.receiver_device_id;
    const peerUserId = deviceId === route.receiver_device_id ? route.sender_user_id : route.receiver_user_id;
    if (peerUserId === this.currentUserId(route, deviceId)) {
      this.sendToDevice(peerDeviceId, message);
    } else {
      await this.sendToPeerUser(peerUserId, peerDeviceId, message);
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const deviceId = this.findDeviceId(ws);
    if (!deviceId) return;
    this.liveWs.delete(deviceId);
    await this.env.SESSIONS.put(`dp:${deviceId}`, JSON.stringify({ last_seen_at: Date.now() }), {
      expirationTtl: 1,
    });
    for (const [sessionId, route] of this.sessions.entries()) {
      if (route.sender_device_id === deviceId || route.receiver_device_id === deviceId) {
        await this.forwardBye(sessionId, route, deviceId, "device_closed");
        this.sessions.delete(sessionId);
      }
    }
  }

  alarm(): void {
    for (const ws of this.liveWs.values()) {
      this.send(ws, { type: "ping" });
    }
  }

  private findDeviceId(ws: WebSocket): string | null {
    for (const [deviceId, candidate] of this.liveWs.entries()) {
      if (candidate === ws) return deviceId;
    }
    return null;
  }

  private registerSessionRoute(payload: SessionRegistration): void {
    if (!payload.sender_device_id || !payload.receiver_device_id) return;
    this.sessions.set(payload.session_id, {
      sender_user_id: payload.sender_user_id,
      sender_device_id: payload.sender_device_id,
      receiver_user_id: payload.receiver_user_id,
      receiver_device_id: payload.receiver_device_id,
      peer_user_id: payload.peer_user_id,
      expires_at: payload.expires_at,
    });
  }

  private currentUserId(route: SessionRoute, deviceId: string): string {
    return deviceId === route.sender_device_id ? route.sender_user_id : route.receiver_user_id;
  }

  private async sendToPeerUser(peerUserId: string, peerDeviceId: string, message: unknown): Promise<void> {
    if (!this.env.SIGNALING_HUB) return;
    const id = this.env.SIGNALING_HUB.idFromName(peerUserId);
    await this.env.SIGNALING_HUB.get(id).fetch("https://signaling.local/dispatchPeerMessage", {
      method: "POST",
      body: JSON.stringify({ target_device_id: peerDeviceId, message }),
    });
  }

  private closeSession(sessionId: string, reason: string): void {
    const route = this.sessions.get(sessionId);
    if (!route) return;
    this.sendToDevice(route.sender_device_id, { type: "bye", session_id: sessionId, reason });
    this.sendToDevice(route.receiver_device_id, { type: "bye", session_id: sessionId, reason });
    this.sessions.delete(sessionId);
  }

  private async forwardBye(sessionId: string, route: SessionRoute, closedDeviceId: string, reason: string): Promise<void> {
    const peerDeviceId = closedDeviceId === route.sender_device_id ? route.receiver_device_id : route.sender_device_id;
    const peerUserId = closedDeviceId === route.sender_device_id ? route.receiver_user_id : route.sender_user_id;
    const message = { type: "bye", session_id: sessionId, reason };
    if (peerUserId === this.currentUserId(route, closedDeviceId)) {
      this.sendToDevice(peerDeviceId, message);
    } else {
      await this.sendToPeerUser(peerUserId, peerDeviceId, message);
    }
  }

  private sendToDevice(deviceId: string, message: unknown): boolean {
    const ws = this.liveWs.get(deviceId);
    if (!ws) return false;
    this.send(ws, message);
    return true;
  }

  private send(ws: WebSocket, message: unknown): void {
    ws.send(JSON.stringify(message));
  }
}
