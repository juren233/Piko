import { describe, expect, it } from "vitest";
import { call } from "./helpers/request.js";

interface AuthSuccess {
  token: string;
  user: { id: string; email: string; username: string; nickname: string | null };
}

interface ErrEnvelope {
  error: { code: string; message: string };
}

interface SearchEnvelope {
  results: Array<{
    id: string;
    username: string;
    nickname: string | null;
    relationship: "none" | "pending-out" | "pending-in" | "friend";
  }>;
}

interface RequestEnvelope {
  request: {
    id: string;
    status: "pending" | "accepted" | "rejected" | "canceled";
    requester_user_id: string;
    receiver_user_id: string;
    other_user?: { id: string; username: string; nickname: string | null };
  };
}

interface RequestsEnvelope {
  requests: RequestEnvelope["request"][];
}

interface FriendsEnvelope {
  friends: Array<{
    user_id: string;
    username: string;
    nickname: string | null;
    online: boolean;
    last_seen_at: number | null;
    since: number;
  }>;
}

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

describe("friends control plane", () => {
  it("searches users by email and username prefix with relationship status", async () => {
    const alice = await register("friendsearchalice");
    const bob = await register("friendsearchbob");

    const byEmail = await call<SearchEnvelope>("GET", "/v1/users/search?q=friendsearchbob@example.com", {
      bearer: alice.token,
    });
    expect(byEmail.status).toBe(200);
    expect(byEmail.json.results).toMatchObject([
      {
        id: bob.user.id,
        username: "friendsearchbob",
        nickname: "FRIENDSEARCHBOB",
        relationship: "none",
      },
    ]);

    const byUsername = await call<SearchEnvelope>("GET", "/v1/users/search?q=friendsearchb", {
      bearer: alice.token,
    });
    expect(byUsername.status).toBe(200);
    expect(byUsername.json.results.map((user) => user.id)).toContain(bob.user.id);
    expect(byUsername.json.results.map((user) => user.id)).not.toContain(alice.user.id);
  });

  it("rejects invalid search query", async () => {
    const alice = await register("friendsearchinvalid");

    const res = await call<ErrEnvelope>("GET", "/v1/users/search?q=a", {
      bearer: alice.token,
    });

    expect(res.status).toBe(400);
    expect(res.json.error.code).toBe("INVALID_SEARCH_QUERY");
  });

  it("creates, lists, accepts and deletes a friendship with canonical visibility", async () => {
    const alice = await register("friendflowalice");
    const bob = await register("friendflowbob");

    const created = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    expect(created.status).toBe(201);
    expect(created.json.request.status).toBe("pending");

    const incoming = await call<RequestsEnvelope>("GET", "/v1/friends/requests?direction=incoming", {
      bearer: bob.token,
    });
    expect(incoming.status).toBe(200);
    expect(incoming.json.requests).toHaveLength(1);
    expect(incoming.json.requests[0]?.other_user?.id).toBe(alice.user.id);

    const forbidden = await call<ErrEnvelope>(
      "POST",
      `/v1/friends/requests/${created.json.request.id}/accept`,
      { bearer: alice.token },
    );
    expect(forbidden.status).toBe(403);
    expect(forbidden.json.error.code).toBe("FRIEND_REQUEST_FORBIDDEN");

    const accepted = await call<RequestEnvelope>(
      "POST",
      `/v1/friends/requests/${created.json.request.id}/accept`,
      { bearer: bob.token },
    );
    expect(accepted.status).toBe(200);
    expect(accepted.json.request.status).toBe("accepted");

    const aliceFriends = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: alice.token });
    const bobFriends = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: bob.token });
    expect(aliceFriends.status).toBe(200);
    expect(bobFriends.status).toBe(200);
    expect(aliceFriends.json.friends.map((friend) => friend.user_id)).toEqual([bob.user.id]);
    expect(bobFriends.json.friends.map((friend) => friend.user_id)).toEqual([alice.user.id]);

    const deleted = await call("DELETE", `/v1/friends/${bob.user.id}`, { bearer: alice.token });
    expect(deleted.status).toBe(204);

    const afterDelete = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: alice.token });
    expect(afterDelete.status).toBe(200);
    expect(afterDelete.json.friends).toHaveLength(0);
  });

  it("prevents self, duplicate and already-friends requests", async () => {
    const alice = await register("friendguardalice");
    const bob = await register("friendguardbob");

    const self = await call<ErrEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: alice.user.id },
    });
    expect(self.status).toBe(400);
    expect(self.json.error.code).toBe("SELF_FRIEND_REQUEST");

    const first = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    expect(first.status).toBe(201);

    const duplicate = await call<ErrEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    expect(duplicate.status).toBe(409);
    expect(duplicate.json.error.code).toBe("FRIEND_REQUEST_EXISTS");

    await call<RequestEnvelope>("POST", `/v1/friends/requests/${first.json.request.id}/accept`, {
      bearer: bob.token,
    });

    const already = await call<ErrEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    expect(already.status).toBe(409);
    expect(already.json.error.code).toBe("ALREADY_FRIENDS");
  });

  it("auto-accepts reverse pending request", async () => {
    const alice = await register("friendreversealice");
    const bob = await register("friendreversebob");

    await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: bob.token,
      body: { receiver_user_id: alice.user.id },
    });

    const autoAccepted = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    expect(autoAccepted.status).toBe(201);
    expect(autoAccepted.json.request.status).toBe("accepted");

    const friends = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: alice.token });
    expect(friends.json.friends.map((friend) => friend.user_id)).toEqual([bob.user.id]);
  });

  it("rejects and cancels pending requests", async () => {
    const alice = await register("friendmutatealice");
    const bob = await register("friendmutatebob");
    const carol = await register("friendmutatecarol");

    const toReject = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    const rejected = await call<RequestEnvelope>(
      "POST",
      `/v1/friends/requests/${toReject.json.request.id}/reject`,
      { bearer: bob.token },
    );
    expect(rejected.status).toBe(200);
    expect(rejected.json.request.status).toBe("rejected");

    const toCancel = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: carol.user.id },
    });
    const canceled = await call<RequestEnvelope>("DELETE", `/v1/friends/requests/${toCancel.json.request.id}`, {
      bearer: alice.token,
    });
    expect(canceled.status).toBe(200);
    expect(canceled.json.request.status).toBe("canceled");
  });

  it("keeps heartbeat compatible without making friends online", async () => {
    const alice = await register("friendpresencealice");
    const bob = await register("friendpresencebob");

    const request = await call<RequestEnvelope>("POST", "/v1/friends/requests", {
      bearer: alice.token,
      body: { receiver_user_id: bob.user.id },
    });
    await call<RequestEnvelope>("POST", `/v1/friends/requests/${request.json.request.id}/accept`, {
      bearer: bob.token,
    });

    const before = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: bob.token });
    expect(before.json.friends[0]?.online).toBe(false);
    expect(before.json.friends[0]?.last_seen_at).toBe(null);

    const heartbeat = await call("POST", "/v1/presence/heartbeat", { bearer: alice.token });
    expect(heartbeat.status).toBe(204);

    const afterHeartbeat = await call<FriendsEnvelope>("GET", "/v1/friends", { bearer: bob.token });
    expect(afterHeartbeat.json.friends[0]?.online).toBe(false);

    expect(afterHeartbeat.json.friends[0]?.last_seen_at).toBe(null);
  });
});
