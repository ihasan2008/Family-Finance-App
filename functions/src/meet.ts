import * as crypto from "node:crypto";

export interface LiveKitVideoGrant {
  room: string;
  roomJoin?: boolean;
  roomCreate?: boolean;
  roomAdmin?: boolean;
  canPublish?: boolean;
  canSubscribe?: boolean;
  canPublishData?: boolean;
}

export interface MintLiveKitJwtInput {
  apiKey: string;
  apiSecret: string;
  identity: string;
  name: string;
  metadata?: string;
  ttlSeconds?: number;
  nowEpochSeconds?: number;
  grant: LiveKitVideoGrant;
}

function base64UrlEncode(input: string | Buffer): string {
  const buf = typeof input === "string" ? Buffer.from(input, "utf8") : input;
  return buf.toString("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

export function buildCanonicalRoomName(familyId: string, meetingId: string): string {
  const cleanFam = familyId.trim().replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40);
  const cleanMeet = meetingId.trim().replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 48);
  if (!cleanFam || !cleanMeet) {
    throw Object.assign(new Error("Valid familyId and meetingId are required for room naming."), {
      statusCode: 400,
      code: "INVALID_MEET_INPUT",
    });
  }
  return `ff_${cleanFam}_${cleanMeet}`;
}

export function normalizeLiveKitHttpBaseUrl(wsOrHttpUrl: string): string {
  const trimmed = wsOrHttpUrl.trim().replace(/\/+$/g, "");
  if (!trimmed) return "";
  if (trimmed.startsWith("wss://")) return `https://${trimmed.slice(6)}`;
  if (trimmed.startsWith("ws://")) return `http://${trimmed.slice(5)}`;
  if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) return trimmed;
  return `https://${trimmed}`;
}

/**
 * Mints a standard LiveKit AccessToken JWT (HS256) strictly on the trusted backend.
 * Compatible with LiveKit Cloud & self-hosted LiveKit SFU servers without exposing LIVEKIT_API_SECRET.
 */
export function mintLiveKitAccessToken(input: MintLiveKitJwtInput): string {
  const apiKey = input.apiKey.trim();
  const apiSecret = input.apiSecret.trim();
  if (!apiKey || !apiSecret) {
    throw Object.assign(
      new Error("LiveKit server credentials (LIVEKIT_API_KEY / LIVEKIT_API_SECRET) are not configured on Cloud Functions."),
      { statusCode: 503, code: "LIVEKIT_NOT_CONFIGURED" }
    );
  }
  const now = input.nowEpochSeconds ?? Math.floor(Date.now() / 1000);
  const ttl = Math.min(7200, Math.max(60, Math.floor(input.ttlSeconds ?? 1800)));

  const header = { alg: "HS256", typ: "JWT" };
  const payload: Record<string, unknown> = {
    iss: apiKey,
    sub: input.identity,
    name: input.name.slice(0, 80),
    nbf: now - 10,
    exp: now + ttl,
    iat: now,
    jti: crypto.randomUUID(),
    video: {
      room: input.grant.room,
      roomJoin: input.grant.roomJoin ?? true,
      roomCreate: input.grant.roomCreate ?? false,
      roomAdmin: input.grant.roomAdmin ?? false,
      canPublish: input.grant.canPublish ?? true,
      canSubscribe: input.grant.canSubscribe ?? true,
      canPublishData: input.grant.canPublishData ?? true,
    },
  };
  if (input.metadata) {
    payload.metadata = input.metadata.slice(0, 512);
  }

  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedPayload = base64UrlEncode(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = crypto
    .createHmac("sha256", apiSecret)
    .update(signingInput, "utf8")
    .digest();
  return `${signingInput}.${base64UrlEncode(signature)}`;
}

export function assertMeetingHostOrAdmin(
  callerUid: string,
  hostUid: string,
  familyData: { ownerId?: unknown; adminUids?: unknown }
) {
  const adminUids = Array.isArray(familyData.adminUids) ? familyData.adminUids : [];
  const isHostOrAdmin =
    callerUid === hostUid || familyData.ownerId === callerUid || adminUids.includes(callerUid);
  if (!isHostOrAdmin) {
    throw Object.assign(
      new Error("Only the meeting host or a Family Admin can perform moderation controls."),
      { statusCode: 403, code: "HOST_OR_ADMIN_REQUIRED" }
    );
  }
}

export async function callLiveKitRoomServiceTwirp(params: {
  livekitUrl: string;
  apiKey: string;
  apiSecret: string;
  rpcMethod: "MutePublishedTrack" | "RemoveParticipant" | "DeleteRoom";
  room: string;
  body: Record<string, unknown>;
}): Promise<{ dispatched: boolean; warning?: string }> {
  const httpBase = normalizeLiveKitHttpBaseUrl(params.livekitUrl);
  if (!httpBase || !params.apiKey.trim() || !params.apiSecret.trim()) {
    return {
      dispatched: false,
      warning: "LIVEKIT_URL or LIVEKIT API credentials are not configured on Cloud Functions; Firestore state updated.",
    };
  }

  const adminToken = mintLiveKitAccessToken({
    apiKey: params.apiKey,
    apiSecret: params.apiSecret,
    identity: "family-finance-moderator",
    name: "Family Finance Server",
    ttlSeconds: 120,
    grant: {
      room: params.room,
      roomAdmin: true,
      roomCreate: true,
      roomJoin: false,
    },
  });

  const endpoint = `${httpBase}/twirp/livekit.RoomService/${params.rpcMethod}`;
  const res = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${adminToken}`,
    },
    body: JSON.stringify(params.body),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    // LiveKit returns 404 if room or track isn't live on SFU yet — keep Firestore moderation authoritative without failing cleanup
    if (res.status === 404) {
      return { dispatched: false, warning: `LiveKit room/track already absent (${text || 404}).` };
    }
    return { dispatched: false, warning: `LiveKit RoomService ${params.rpcMethod} returned HTTP ${res.status}: ${text}` };
  }
  return { dispatched: true };
}
