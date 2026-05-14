import { Hono } from "hono";
import type { AppVariables, Env } from "../env.js";
import { requireAuth } from "../auth/middleware.js";
import { AppError } from "../errors.js";
import { areFriends } from "../db/friendships.js";
import { blobToBase64, findActiveDevice } from "../db/devices.js";
import { parseTransferSessionBody } from "../validation/schemas.js";
import { p2pIceServers } from "../ice.js";

export const transferSessionsRoute = new Hono<{ Bindings: Env; Variables: AppVariables }>();

transferSessionsRoute.use("*", requireAuth);

interface StoredTransferSession {
  sender_user_id: string;
  sender_device_id: string;
  receiver_user_id: string;
  receiver_device_id: string;
  expires_at: number;
}

transferSessionsRoute.post("/", async (c) => {
  const currentUser = c.get("user");
  const input = parseTransferSessionBody(await c.req.json().catch(() => null));

  if (input.receiverUserId !== currentUser.id) {
    const friends = await areFriends(c.env, currentUser.id, input.receiverUserId);
    if (!friends) throw new AppError("TRANSFER_PEER_NOT_FRIEND");
  }

  const device = await findActiveDevice(c.env, input.receiverDeviceId);
  if (!device || device.user_id !== input.receiverUserId) {
    throw new AppError("DEVICE_NOT_FOUND");
  }

  const senderDeviceId = input.senderDeviceId;
  if (!senderDeviceId) throw new AppError("DEVICE_NOT_FOUND");
  const senderDevice = await findActiveDevice(c.env, senderDeviceId);
  if (!senderDevice || senderDevice.user_id !== currentUser.id) {
    throw new AppError("DEVICE_NOT_OWNED");
  }

  const sessionId = crypto.randomUUID();
  const expiresAt = Date.now() + 30 * 60 * 1000;
  const iceServers = p2pIceServers();
  const route: StoredTransferSession = {
    sender_user_id: currentUser.id,
    sender_device_id: senderDeviceId,
    receiver_user_id: input.receiverUserId,
    receiver_device_id: input.receiverDeviceId,
    expires_at: expiresAt,
  };
  if (!c.env.SIGNALING_HUB) throw new AppError("DEVICE_OFFLINE");
  await c.env.SIGNALING_HUB.get(c.env.SIGNALING_HUB.idFromName(currentUser.id)).fetch(
    "https://signaling.local/registerSession",
    {
      method: "POST",
      body: JSON.stringify({
        session_id: sessionId,
        sender_user_id: currentUser.id,
        sender_device_id: senderDeviceId,
        receiver_user_id: input.receiverUserId,
        receiver_device_id: input.receiverDeviceId,
        peer_user_id: input.receiverUserId,
        expires_at: expiresAt,
      }),
    },
  );
  const id = c.env.SIGNALING_HUB.idFromName(input.receiverUserId);
  const inviteResponse = await c.env.SIGNALING_HUB.get(id).fetch("https://signaling.local/dispatchInvite", {
    method: "POST",
    body: JSON.stringify({
      session_id: sessionId,
      from_device_id: senderDeviceId,
      from_user_id: currentUser.id,
      receiver_user_id: input.receiverUserId,
      receiver_device_id: input.receiverDeviceId,
      transfer_id: input.transferId,
      manifest_hash_b64: input.manifestHashB64,
      sender_x25519_eph_pub_b64: input.senderX25519EphPubB64,
      sender_invite_signature_b64: input.senderInviteSignatureB64,
      sender_ed25519_pub_b64: blobToBase64(senderDevice.ed25519_pub),
      sender_x25519_pub_b64: blobToBase64(senderDevice.x25519_pub),
      ice_servers: iceServers,
      same_account: input.receiverUserId === currentUser.id,
      expires_at: expiresAt,
    }),
  });
  const inviteResult = (await inviteResponse.json().catch(() => ({ delivered: false }))) as { delivered?: boolean };
  if (!inviteResult.delivered) {
    await c.env.SIGNALING_HUB.get(c.env.SIGNALING_HUB.idFromName(currentUser.id)).fetch(
      "https://signaling.local/closeSession",
      {
        method: "POST",
        body: JSON.stringify({ session_id: sessionId, reason: "receiver_offline" }),
      },
    );
    throw new AppError("DEVICE_OFFLINE");
  }
  await c.env.SESSIONS.put(`tr:${sessionId}`, JSON.stringify(route), {
    expirationTtl: 30 * 60,
  });

  return c.json(
    {
      session_id: sessionId,
      ice_servers: iceServers,
      expires_at: expiresAt,
    },
    201,
  );
});

transferSessionsRoute.get("/:id", (c) => {
  const id = c.req.param("id");
  if (id.length === 0) throw new AppError("TRANSFER_SESSION_NOT_FOUND");
  return c.json({ session_id: id, status: "ephemeral" });
});

transferSessionsRoute.post("/:id/finish", async (c) => {
  const currentUser = c.get("user");
  const id = c.req.param("id");
  if (id.length === 0) throw new AppError("TRANSFER_SESSION_NOT_FOUND");
  const raw = await c.env.SESSIONS.get(`tr:${id}`);
  if (!raw) throw new AppError("TRANSFER_SESSION_NOT_FOUND");
  const route = JSON.parse(raw) as StoredTransferSession;
  if (currentUser.id !== route.sender_user_id && currentUser.id !== route.receiver_user_id) {
    throw new AppError("TRANSFER_SESSION_FORBIDDEN");
  }
  if (c.env.SIGNALING_HUB) {
    await Promise.all(
      [route.sender_user_id, route.receiver_user_id].map((userId) =>
        c.env.SIGNALING_HUB!.get(c.env.SIGNALING_HUB!.idFromName(userId)).fetch("https://signaling.local/closeSession", {
          method: "POST",
          body: JSON.stringify({ session_id: id, reason: "finished" }),
        }),
      ),
    );
  }
  await c.env.SESSIONS.delete(`tr:${id}`);
  return c.body(null, 204);
});
