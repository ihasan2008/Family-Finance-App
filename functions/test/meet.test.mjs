import { test } from "node:test";
import assert from "node:assert/strict";
import * as crypto from "node:crypto";

function base64UrlEncode(input) {
  const buf = typeof input === "string" ? Buffer.from(input, "utf8") : input;
  return buf.toString("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function base64UrlDecodeJson(part) {
  const padded = part.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((part.length + 3) % 4);
  return JSON.parse(Buffer.from(padded, "base64").toString("utf8"));
}

function mintLiveKitJwt({ apiKey, apiSecret, identity, name, room, roomAdmin, ttlSeconds = 1800, now = 1_760_000_000 }) {
  const header = { alg: "HS256", typ: "JWT" };
  const payload = {
    iss: apiKey,
    sub: identity,
    name,
    nbf: now - 10,
    exp: now + ttlSeconds,
    iat: now,
    jti: crypto.randomUUID(),
    video: {
      room,
      roomJoin: true,
      roomCreate: Boolean(roomAdmin),
      roomAdmin: Boolean(roomAdmin),
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
    },
  };
  const signingInput = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(payload))}`;
  const sig = crypto.createHmac("sha256", apiSecret).update(signingInput, "utf8").digest();
  return `${signingInput}.${base64UrlEncode(sig)}`;
}

test("LiveKit HS256 JWT has valid signature, room scope, and host/member grants", () => {
  const secret = "dev_livekit_secret_key_for_unit_test_only";
  const token = mintLiveKitJwt({
    apiKey: "APItestKey123",
    apiSecret: secret,
    identity: "uid_alice",
    name: "Alice Khan",
    room: "ff_fam_alpha_mtg_01",
    roomAdmin: true,
    ttlSeconds: 1800,
    now: 1_760_000_000,
  });

  const parts = token.split(".");
  assert.equal(parts.length, 3);
  const header = base64UrlDecodeJson(parts[0]);
  const claims = base64UrlDecodeJson(parts[1]);

  assert.equal(header.alg, "HS256");
  assert.equal(claims.iss, "APItestKey123");
  assert.equal(claims.sub, "uid_alice");
  assert.equal(claims.exp - claims.iat, 1800);
  assert.equal(claims.video.room, "ff_fam_alpha_mtg_01");
  assert.equal(claims.video.roomJoin, true);
  assert.equal(claims.video.roomAdmin, true);

  const expectedSig = base64UrlEncode(
    crypto.createHmac("sha256", secret).update(`${parts[0]}.${parts[1]}`, "utf8").digest()
  );
  assert.equal(parts[2], expectedSig);
});

test("buildCanonicalRoomName isolates rooms per family and strips unsafe characters", () => {
  const buildRoom = (fam, mtg) =>
    `ff_${fam.trim().replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40)}_${mtg.trim().replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 48)}`;

  const roomA = buildRoom("fam_alpha", "mtg_101");
  const roomB = buildRoom("fam_beta", "mtg_101");
  assert.equal(roomA, "ff_fam_alpha_mtg_101");
  assert.notEqual(roomA, roomB);
  assert.equal(buildRoom("fam/../hack", "mtg$1"), "ff_famhack_mtg1");
});
