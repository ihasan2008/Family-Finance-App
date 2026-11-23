import { onRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} from "@simplewebauthn/server";
import * as crypto from "node:crypto";
import {
  computeAndroidApkKeyHashOrigin,
  evaluateSlidingRateLimit,
  generateInvitationToken,
  generateMemberCardId16,
  generateRandomFamilyId,
  hashInvitationToken,
  hashPasswordScrypt,
  hashPhoneIndexKey,
  isValidFamilyId12,
  maskPhoneNumber,
  normalizeE164Phone,
  normalizeFamilyIdInput,
  resolveMemberIdFromPhone,
  validateDateOfBirthIso,
  validatePasswordStrength,
  verifyPasswordScrypt,
} from "./security";
import {
  assertMeetingHostOrAdmin,
  buildCanonicalRoomName,
  callLiveKitRoomServiceTwirp,
  mintLiveKitAccessToken,
} from "./meet";
import {
  ApprovalRulesConfig,
  assertValidCancelReason,
  buildExecutionDescriptor,
  DECISION_CATEGORIES,
  DecisionCategory,
  DEFAULT_APPROVAL_RULES,
  evaluateProposalTally,
  ProposalStatus,
  resolveEligibleVoters,
  validateApprovalRulesConfig,
  VoteChoice,
} from "./decisions";

admin.initializeApp();

const DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-familyfi-731bf1ff-3c53-49b0-b830-fda840c8610f";
const RP_ID = process.env.WEBAUTHN_RP_ID || "gen-lang-client-0265273815.web.app";
const RP_NAME = "Family Finance";
const EXPECTED_ORIGINS = [
  `https://${RP_ID}`,
  computeAndroidApkKeyHashOrigin(
    process.env.ANDROID_SHA256_FINGERPRINT ||
      "7E:D3:09:9B:FD:3F:01:9B:97:53:64:5C:33:B6:A4:AD:8D:B5:08:41:C7:B6:12:AA:A6:C3:66:B4:F2:7A:B7:8F"
  ),
];

const db = getFirestore(DATABASE_ID);

async function verifyBearerUser(req: { headers: Record<string, string | string[] | undefined> }) {
  const authHeader = req.headers.authorization;
  if (typeof authHeader !== "string" || !authHeader.startsWith("Bearer ")) {
    throw Object.assign(new Error("Missing Bearer ID token."), { statusCode: 401, code: "UNAUTHENTICATED" });
  }
  const token = authHeader.slice(7).trim();
  const decoded = await admin.auth().verifyIdToken(token, true);
  return decoded;
}

async function verifyOptionalAppCheck(req: { headers: Record<string, string | string[] | undefined> }) {
  const appCheckToken = req.headers["x-firebase-appcheck"];
  if (typeof appCheckToken === "string" && appCheckToken.trim().length > 0) {
    await admin.appCheck().verifyToken(appCheckToken.trim());
  } else if (process.env.ENFORCE_APP_CHECK === "true") {
    throw Object.assign(new Error("App Check token missing."), { statusCode: 401, code: "APP_CHECK_REQUIRED" });
  }
}

async function enforceRateLimit(key: string, maxAttempts: number, windowMs: number, lockoutMs: number) {
  const ref = db.collection("auth_rate_limits").doc(key);
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data() || {};
    const windowStartMs = typeof data.windowStartMs === "number" ? data.windowStartMs : now;
    const attemptCount = typeof data.attemptCount === "number" ? data.attemptCount : 0;
    const lockedUntilMs = typeof data.lockedUntilMs === "number" ? data.lockedUntilMs : null;

    const decision = evaluateSlidingRateLimit(
      now,
      windowStartMs,
      attemptCount,
      lockedUntilMs,
      maxAttempts,
      windowMs,
      lockoutMs
    );

    if (!decision.allowed) {
      tx.set(
        ref,
        {
          windowStartMs: now - windowStartMs < windowMs ? windowStartMs : now,
          attemptCount: decision.nextAttemptCount,
          lockedUntilMs: decision.lockedUntilMs,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
      throw Object.assign(
        new Error(`Too many attempts. Retry in ${decision.retryAfterSeconds}s.`),
        { statusCode: 429, code: "RATE_LIMITED", retryAfterSeconds: decision.retryAfterSeconds }
      );
    }

    tx.set(
      ref,
      {
        windowStartMs: now - windowStartMs < windowMs ? windowStartMs : now,
        attemptCount: decision.nextAttemptCount,
        lockedUntilMs: null,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  });
}

async function clearRateLimit(key: string) {
  await db.collection("auth_rate_limits").doc(key).delete().catch(() => undefined);
}

function sendError(res: any, err: any) {
  const status = typeof err?.statusCode === "number" ? err.statusCode : 400;
  res.status(status).json({
    ok: false,
    code: err?.code || "AUTH_ERROR",
    message: err?.message || "Authentication request failed.",
    retryAfterSeconds: err?.retryAfterSeconds ?? null,
  });
}

async function assertFamilyAdmin(familyId: string, uid: string) {
  const famRef = db.collection("families").doc(familyId);
  const famSnap = await famRef.get();
  if (!famSnap.exists) {
    throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
  }
  const fam = famSnap.data()!;
  const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];
  if (fam.ownerId !== uid && !adminUids.includes(uid)) {
    throw Object.assign(new Error("Only a Family Admin can perform this operation."), { statusCode: 403, code: "ADMIN_REQUIRED" });
  }
  return { famRef, fam };
}

async function syncDenormalizedMemberLists(
  tx: FirebaseFirestore.Transaction,
  familyRef: FirebaseFirestore.DocumentReference,
  memberUids: string[],
  adminUids: string[],
  skipUid?: string
) {
  const membersSnap = await tx.get(familyRef.collection("members"));
  membersSnap.forEach((doc) => {
    if (doc.id !== skipUid) {
      tx.update(doc.ref, {
        memberUids,
        adminUids,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  });
}

function writeAuditLogInTx(
  tx: FirebaseFirestore.Transaction,
  familyId: string,
  actorUserId: string,
  entityCollection: string,
  entityId: string,
  action: "CREATE" | "UPDATE" | "DELETE" | "ARCHIVE" | "STATUS_CHANGE" | "MEMBER_CHANGE" | "SYNC",
  summary: string,
  memberUids: string[]
) {
  const auditId = `aud_${crypto.randomBytes(8).toString("hex")}`;
  tx.set(db.collection("auditLogs").doc(auditId), {
    auditId,
    familyId,
    actorUserId,
    entityCollection,
    entityId,
    action,
    summary: summary.slice(0, 240),
    memberUids: memberUids.slice(0, 20),
    createdAt: FieldValue.serverTimestamp(),
  });
}

function writeNotificationInTx(
  tx: FirebaseFirestore.Transaction,
  familyId: string,
  recipientUserId: string,
  createdByUserId: string,
  title: string,
  bodyText: string,
  notificationType: string,
  deepLinkModule: string,
  relatedRecordId: string,
  memberUids: string[]
) {
  const notificationId = `notif_${crypto.randomBytes(8).toString("hex")}`;
  tx.set(db.collection("notifications").doc(notificationId), {
    notificationId,
    familyId,
    recipientUserId,
    createdByUserId,
    title: title.slice(0, 100),
    body: bodyText.slice(0, 400),
    notificationType: notificationType.slice(0, 40),
    deepLinkModule: deepLinkModule.slice(0, 40),
    relatedRecordId: relatedRecordId.slice(0, 80),
    acknowledged: false,
    readByUids: [],
    recordStatus: "ACTIVE",
    memberUids: memberUids.slice(0, 20),
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  });
}

export const authApi = onRequest({ region: "us-central1", cors: true }, async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ ok: false, code: "METHOD_NOT_ALLOWED", message: "Use POST." });
      return;
    }
    await verifyOptionalAppCheck(req);

    const route = req.path.replace(/^\/+|\/+$/g, "");
    const body = (req.body || {}) as Record<string, any>;

    switch (route) {
      case "requestOtpGuard": {
        const phone = normalizeE164Phone(String(body.phoneNumber || ""));
        const deviceId = String(body.deviceId || "unknown");
        const phoneHash = hashPhoneIndexKey(phone);
        await enforceRateLimit(`otp_${phoneHash}`, 5, 60 * 60 * 1000, 15 * 60 * 1000);
        await enforceRateLimit(`otp_dev_${crypto.createHash("sha256").update(deviceId).digest("hex")}`, 10, 60 * 60 * 1000, 15 * 60 * 1000);
        res.json({ ok: true, cooldownSeconds: 60, otpExpirySeconds: 120 });
        return;
      }

      case "registerMemberPasswordless": {
        const decoded = await verifyBearerUser(req);
        const phone = resolveMemberIdFromPhone(String(body.phoneNumber || decoded.phone_number || ""));
        if (decoded.phone_number !== phone) {
          throw Object.assign(new Error("Mobile number mismatch with verified SMS OTP session."), {
            statusCode: 403,
            code: "OTP_PHONE_MISMATCH",
          });
        }
        const fullName = String(body.fullName || body.displayName || "").trim().slice(0, 80);
        if (fullName.length < 2) {
          throw Object.assign(new Error("Full Name must be at least 2 characters."), {
            statusCode: 400,
            code: "INVALID_INPUT",
          });
        }
        const dateOfBirth = validateDateOfBirthIso(String(body.dateOfBirth || ""));
        const deviceId = String(body.deviceId || "");
        const deviceLabel = String(body.deviceLabel || "Android Device").slice(0, 80);
        const sessionId = String(body.sessionId || crypto.randomUUID());
        const phoneHash = hashPhoneIndexKey(phone);
        const maskedPhone = maskPhoneNumber(phone);
        const uid = decoded.uid;

        await db.runTransaction(async (tx) => {
          const existingPhoneIdx = await tx.get(db.collection("phone_index").doc(phoneHash));
          if (existingPhoneIdx.exists && existingPhoneIdx.data()?.uid !== uid) {
            throw Object.assign(new Error("This mobile number is already linked to another account."), {
              statusCode: 409,
              code: "PHONE_ALREADY_REGISTERED",
            });
          }
          tx.set(db.collection("phone_index").doc(phoneHash), {
            uid,
            phoneNumber: phone,
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(
            db.collection("users").doc(uid),
            {
              userId: uid,
              displayName: fullName,
              email: decoded.email || `${uid.slice(0, 12)}@phone.familyfinance.app`,
              phoneNumber: phone,
              memberId: phone,
              maskedPhone,
              dateOfBirth,
              accountStatus: "ACTIVE",
              passwordConfigured: false,
              newDeviceCheckEnabled: true,
              themePreference: "SYSTEM",
              registeredAt: FieldValue.serverTimestamp(),
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
          if (deviceId) {
            tx.set(db.collection("users").doc(uid).collection("trusted_devices").doc(deviceId), {
              deviceId,
              deviceLabel,
              trustedAt: FieldValue.serverTimestamp(),
              lastVerifiedAt: FieldValue.serverTimestamp(),
            });
          }
          tx.set(db.collection("users").doc(uid).collection("sessions").doc(sessionId), {
            sessionId,
            userId: uid,
            deviceId: deviceId || sessionId,
            deviceLabel,
            platform: "Android",
            authMethod: "PHONE_OTP",
            isTrustedDevice: true,
            revoked: false,
            createdAt: FieldValue.serverTimestamp(),
            lastActiveAt: FieldValue.serverTimestamp(),
          });
        });

        await admin.auth().updateUser(uid, { displayName: fullName });
        res.json({
          ok: true,
          uid,
          memberId: phone,
          maskedPhone,
          accountStatus: "ACTIVE",
        });
        return;
      }

      case "changeVerifiedMobileNumber": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        await enforceRateLimit(`change_phone_${uid}`, 5, 60 * 60 * 1000, 15 * 60 * 1000);

        const newPhone = resolveMemberIdFromPhone(String(body.newPhoneNumber || ""));
        const newPhoneIdToken = String(body.newPhoneIdToken || "").trim();
        const revokeOtherSessions = body.revokeOtherSessions === true;
        const keepSessionId = typeof body.keepSessionId === "string" ? body.keepSessionId : null;

        let verifiedNewPhone: string | undefined;
        if (newPhoneIdToken) {
          const decodedNew = await admin.auth().verifyIdToken(newPhoneIdToken, true);
          const nowSec = Math.floor(Date.now() / 1000);
          if (nowSec - Number(decodedNew.auth_time || 0) > 300) {
            throw Object.assign(new Error("SMS OTP verification expired. Request a fresh verification code."), {
              statusCode: 401,
              code: "OTP_EXPIRED",
            });
          }
          verifiedNewPhone = decodedNew.phone_number;
        } else {
          verifiedNewPhone = decoded.phone_number;
        }

        if (verifiedNewPhone !== newPhone) {
          throw Object.assign(new Error("Verified SMS OTP phone number does not match the new mobile number."), {
            statusCode: 403,
            code: "OTP_PHONE_MISMATCH",
          });
        }

        const newPhoneHash = hashPhoneIndexKey(newPhone);
        const newMasked = maskPhoneNumber(newPhone);

        await db.runTransaction(async (tx) => {
          const newPhoneIdxSnap = await tx.get(db.collection("phone_index").doc(newPhoneHash));
          if (newPhoneIdxSnap.exists && newPhoneIdxSnap.data()?.uid !== uid) {
            throw Object.assign(new Error("This mobile number is already registered to another member."), {
              statusCode: 409,
              code: "PHONE_ALREADY_REGISTERED",
            });
          }

          const userRef = db.collection("users").doc(uid);
          const userSnap = await tx.get(userRef);
          const oldPhone = userSnap.data()?.phoneNumber ? String(userSnap.data()?.phoneNumber) : null;
          if (oldPhone && oldPhone !== newPhone) {
            const oldPhoneHash = hashPhoneIndexKey(oldPhone);
            const oldIdxRef = db.collection("phone_index").doc(oldPhoneHash);
            const oldIdxSnap = await tx.get(oldIdxRef);
            if (oldIdxSnap.exists && oldIdxSnap.data()?.uid === uid) {
              tx.delete(oldIdxRef);
            }
          }

          tx.set(db.collection("phone_index").doc(newPhoneHash), {
            uid,
            phoneNumber: newPhone,
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(
            userRef,
            {
              phoneNumber: newPhone,
              memberId: newPhone,
              maskedPhone: newMasked,
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

          const famSnap = await tx.get(db.collection("families").where("memberUids", "array-contains", uid));
          famSnap.docs.forEach((famDoc) => {
            const famData = famDoc.data();
            const memberUids: string[] = Array.isArray(famData.memberUids) ? famData.memberUids : [uid];
            tx.set(
              famDoc.ref.collection("members").doc(uid),
              {
                memberId: newPhone,
                maskedPhone: newMasked,
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true }
            );
            writeAuditLogInTx(
              tx,
              famDoc.id,
              uid,
              "members",
              uid,
              "UPDATE",
              `Member updated verified Mobile Member ID (${newMasked})`,
              memberUids
            );
          });
        });

        if (revokeOtherSessions) {
          await admin.auth().revokeRefreshTokens(uid);
          const snap = await db.collection("users").doc(uid).collection("sessions").where("revoked", "==", false).get();
          const batch = db.batch();
          snap.docs.forEach((doc) => {
            if (doc.id !== keepSessionId) {
              batch.update(doc.ref, { revoked: true, revokedAt: FieldValue.serverTimestamp() });
            }
          });
          await batch.commit();
        }

        res.json({ ok: true, uid, memberId: newPhone, maskedPhone: newMasked });
        return;
      }

      case "registerWithPhonePassword": {
        const decoded = await verifyBearerUser(req);
        const phone = normalizeE164Phone(String(body.phoneNumber || decoded.phone_number || ""));
        if (decoded.phone_number !== phone) {
          throw Object.assign(new Error("Phone number mismatch with verified SMS OTP session."), { statusCode: 403, code: "OTP_PHONE_MISMATCH" });
        }
        const password = String(body.password || "");
        const displayName = String(body.displayName || "Family Member").trim().slice(0, 80) || "Family Member";
        const deviceId = String(body.deviceId || "");
        const deviceLabel = String(body.deviceLabel || "Android Device").slice(0, 80);
        const sessionId = String(body.sessionId || crypto.randomUUID());

        const { saltHex, hashHex } = hashPasswordScrypt(password);
        const phoneHash = hashPhoneIndexKey(phone);
        const maskedPhone = maskPhoneNumber(phone);
        const uid = decoded.uid;

        await db.runTransaction(async (tx) => {
          const existingPhoneIdx = await tx.get(db.collection("phone_index").doc(phoneHash));
          if (existingPhoneIdx.exists && existingPhoneIdx.data()?.uid !== uid) {
            throw Object.assign(new Error("This mobile number is already linked to another account."), { statusCode: 409, code: "PHONE_ALREADY_REGISTERED" });
          }
          tx.set(db.collection("phone_index").doc(phoneHash), {
            uid,
            phoneNumber: phone,
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(db.collection("auth_credentials").doc(uid), {
            uid,
            phoneHash,
            passwordSalt: saltHex,
            passwordHash: hashHex,
            algorithm: "scrypt-N16384-r8-p1",
            accountStatus: "ACTIVE",
            passwordUpdatedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(
            db.collection("users").doc(uid),
            {
              userId: uid,
              displayName,
              email: decoded.email || `${uid.slice(0, 12)}@phone.familyfinance.app`,
              phoneNumber: phone,
              memberId: phone,
              maskedPhone,
              accountStatus: "ACTIVE",
              passwordConfigured: true,
              newDeviceCheckEnabled: true,
              themePreference: "SYSTEM",
              registeredAt: FieldValue.serverTimestamp(),
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
          if (deviceId) {
            tx.set(db.collection("users").doc(uid).collection("trusted_devices").doc(deviceId), {
              deviceId,
              deviceLabel,
              trustedAt: FieldValue.serverTimestamp(),
              lastVerifiedAt: FieldValue.serverTimestamp(),
            });
          }
          tx.set(db.collection("users").doc(uid).collection("sessions").doc(sessionId), {
            sessionId,
            userId: uid,
            deviceId: deviceId || sessionId,
            deviceLabel,
            platform: "Android",
            authMethod: "PHONE_OTP",
            isTrustedDevice: true,
            revoked: false,
            createdAt: FieldValue.serverTimestamp(),
            lastActiveAt: FieldValue.serverTimestamp(),
          });
        });

        await admin.auth().updateUser(uid, { displayName });
        res.json({ ok: true, uid, memberId: phone, maskedPhone, accountStatus: "ACTIVE" });
        return;
      }

      case "loginWithPhonePassword": {
        const phone = normalizeE164Phone(String(body.phoneNumber || ""));
        const password = String(body.password || "");
        const deviceId = String(body.deviceId || "");
        const deviceLabel = String(body.deviceLabel || "Android Device").slice(0, 80);
        const otpIdToken = typeof body.otpIdToken === "string" ? body.otpIdToken.trim() : null;
        const phoneHash = hashPhoneIndexKey(phone);

        await enforceRateLimit(`login_${phoneHash}`, 5, 15 * 60 * 1000, 15 * 60 * 1000);

        const phoneSnap = await db.collection("phone_index").doc(phoneHash).get();
        if (!phoneSnap.exists) {
          throw Object.assign(new Error("Invalid mobile number or password."), { statusCode: 401, code: "INVALID_CREDENTIALS" });
        }
        const uid = String(phoneSnap.data()?.uid || "");
        const credSnap = await db.collection("auth_credentials").doc(uid).get();
        const credData = credSnap.data();
        if (!credData || credData.accountStatus !== "ACTIVE") {
          throw Object.assign(new Error("Account is locked or inactive. Use SMS OTP Account Recovery."), { statusCode: 403, code: "ACCOUNT_LOCKED" });
        }

        const verified = verifyPasswordScrypt(password, credData.passwordSalt, credData.passwordHash);
        if (!verified) {
          throw Object.assign(new Error("Invalid mobile number or password."), { statusCode: 401, code: "INVALID_CREDENTIALS" });
        }

        const userDoc = await db.collection("users").doc(uid).get();
        const newDeviceCheckEnabled = userDoc.data()?.newDeviceCheckEnabled !== false;
        const trustedRef = deviceId ? db.collection("users").doc(uid).collection("trusted_devices").doc(deviceId) : null;
        const trustedSnap = trustedRef ? await trustedRef.get() : null;

        if (newDeviceCheckEnabled && (!trustedSnap || !trustedSnap.exists)) {
          if (!otpIdToken) {
            res.status(403).json({
              ok: false,
              code: "NEW_DEVICE_REQUIRED",
              message: "New device detected. Verify with SMS OTP to trust this device and complete login.",
              uid,
            });
            return;
          }
          const decodedOtp = await admin.auth().verifyIdToken(otpIdToken, true);
          if (decodedOtp.phone_number !== phone) {
            throw Object.assign(new Error("OTP verification phone number does not match."), { statusCode: 403, code: "OTP_PHONE_MISMATCH" });
          }
          if (trustedRef) {
            await trustedRef.set({
              deviceId,
              deviceLabel,
              trustedAt: FieldValue.serverTimestamp(),
              lastVerifiedAt: FieldValue.serverTimestamp(),
            });
          }
        }

        await clearRateLimit(`login_${phoneHash}`);
        const sessionId = crypto.randomUUID();
        await db.collection("users").doc(uid).collection("sessions").doc(sessionId).set({
          sessionId,
          userId: uid,
          deviceId: deviceId || sessionId,
          deviceLabel,
          platform: "Android",
          authMethod: "PHONE_PASSWORD",
          isTrustedDevice: true,
          revoked: false,
          createdAt: FieldValue.serverTimestamp(),
          lastActiveAt: FieldValue.serverTimestamp(),
        });
        const customToken = await admin.auth().createCustomToken(uid, { authMethod: "PHONE_PASSWORD", sessionId });
        res.json({ ok: true, uid, customToken, sessionId });
        return;
      }

      case "resetPasswordWithOtp": {
        const decoded = await verifyBearerUser(req);
        const phone = normalizeE164Phone(String(body.phoneNumber || decoded.phone_number || ""));
        if (decoded.phone_number !== phone) {
          throw Object.assign(new Error("Verified SMS OTP phone mismatch."), { statusCode: 403, code: "OTP_PHONE_MISMATCH" });
        }
        const nowSec = Math.floor(Date.now() / 1000);
        if (nowSec - decoded.auth_time > 300) {
          throw Object.assign(new Error("SMS OTP verification expired. Please request a fresh code."), { statusCode: 401, code: "OTP_EXPIRED" });
        }
        const newPassword = String(body.newPassword || "");
        const { saltHex, hashHex } = hashPasswordScrypt(newPassword);
        const phoneHash = hashPhoneIndexKey(phone);
        const uid = decoded.uid;

        await db.collection("auth_credentials").doc(uid).set(
          {
            uid,
            phoneHash,
            passwordSalt: saltHex,
            passwordHash: hashHex,
            algorithm: "scrypt-N16384-r8-p1",
            accountStatus: "ACTIVE",
            passwordUpdatedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
        await db.collection("phone_index").doc(phoneHash).set({ uid, phoneNumber: phone, updatedAt: FieldValue.serverTimestamp() });
        await db.collection("users").doc(uid).set({ passwordConfigured: true, accountStatus: "ACTIVE", updatedAt: FieldValue.serverTimestamp() }, { merge: true });

        await admin.auth().revokeRefreshTokens(uid);
        const sessionsSnap = await db.collection("users").doc(uid).collection("sessions").where("revoked", "==", false).get();
        const batch = db.batch();
        sessionsSnap.forEach((doc) => {
          batch.update(doc.ref, { revoked: true, revokedAt: FieldValue.serverTimestamp() });
        });
        await batch.commit();

        const customToken = await admin.auth().createCustomToken(uid, { authMethod: "PASSWORD_RESET_OTP" });
        res.json({ ok: true, uid, customToken });
        return;
      }

      case "beginPasskeyRegistration": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const passkeysSnap = await db.collection("users").doc(uid).collection("passkeys").get();
        const excludeCredentials = passkeysSnap.docs.map((d) => ({
          id: d.id,
          transports: (d.data().transports as any[]) || ["internal"],
        }));
        const options = await generateRegistrationOptions({
          rpName: RP_NAME,
          rpID: RP_ID,
          userID: Buffer.from(uid, "utf8"),
          userName: decoded.phone_number || decoded.email || uid,
          userDisplayName: decoded.name || decoded.phone_number || "Family Member",
          authenticatorSelection: {
            authenticatorAttachment: "platform",
            residentKey: "required",
            userVerification: "required",
          },
          excludeCredentials,
        });

        await db.collection("webauthn_challenges").doc(`reg_${uid}`).set({
          challenge: options.challenge,
          uid,
          type: "registration",
          expiresAt: Timestamp.fromMillis(Date.now() + 5 * 60 * 1000),
        });

        res.json({ ok: true, optionsJson: JSON.stringify(options) });
        return;
      }

      case "finishPasskeyRegistration": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const responseJson = JSON.parse(String(body.registrationResponseJson || "{}"));
        const label = String(body.label || "Android Passkey").slice(0, 60);

        const chSnap = await db.collection("webauthn_challenges").doc(`reg_${uid}`).get();
        const chData = chSnap.data();
        if (!chData || chData.expiresAt.toMillis() < Date.now()) {
          throw Object.assign(new Error("Passkey registration challenge expired."), { statusCode: 400, code: "CHALLENGE_EXPIRED" });
        }

        const verification = await verifyRegistrationResponse({
          response: responseJson,
          expectedChallenge: chData.challenge,
          expectedOrigin: EXPECTED_ORIGINS,
          expectedRPID: RP_ID,
          requireUserVerification: true,
        });

        if (!verification.verified || !verification.registrationInfo) {
          throw Object.assign(new Error("Passkey attestation could not be verified."), { statusCode: 400, code: "WEBAUTHN_VERIFICATION_FAILED" });
        }

        const { credential, credentialBackedUp } = verification.registrationInfo;
        await db.collection("users").doc(uid).collection("passkeys").doc(credential.id).set({
          credentialId: credential.id,
          userId: uid,
          label,
          rpId: RP_ID,
          publicKeyBase64Url: Buffer.from(credential.publicKey).toString("base64url"),
          counter: credential.counter,
          transports: credential.transports || ["internal"],
          backedUp: credentialBackedUp,
          createdAt: FieldValue.serverTimestamp(),
          lastUsedAt: FieldValue.serverTimestamp(),
        });
        await db.collection("webauthn_challenges").doc(`reg_${uid}`).delete();

        const countSnap = await db.collection("users").doc(uid).collection("passkeys").count().get();
        await db.collection("users").doc(uid).set({ passkeyCount: countSnap.data().count, updatedAt: FieldValue.serverTimestamp() }, { merge: true });

        res.json({ ok: true, credentialId: credential.id });
        return;
      }

      case "beginPasskeyAuthentication": {
        const phone = body.phoneNumber ? normalizeE164Phone(String(body.phoneNumber)) : null;
        let uid: string | null = null;
        let allowCredentials: any[] = [];
        if (phone) {
          const phoneHash = hashPhoneIndexKey(phone);
          const phoneSnap = await db.collection("phone_index").doc(phoneHash).get();
          if (phoneSnap.exists) {
            uid = String(phoneSnap.data()?.uid || "");
            const passkeysSnap = await db.collection("users").doc(uid).collection("passkeys").get();
            allowCredentials = passkeysSnap.docs.map((d) => ({ id: d.id, transports: d.data().transports || ["internal"] }));
          }
        }
        const options = await generateAuthenticationOptions({
          rpID: RP_ID,
          userVerification: "required",
          allowCredentials,
        });
        const challengeId = crypto.randomUUID();
        await db.collection("webauthn_challenges").doc(challengeId).set({
          challenge: options.challenge,
          uid,
          type: "authentication",
          expiresAt: Timestamp.fromMillis(Date.now() + 5 * 60 * 1000),
        });
        res.json({ ok: true, challengeId, optionsJson: JSON.stringify(options) });
        return;
      }

      case "finishPasskeyAuthentication": {
        const challengeId = String(body.challengeId || "");
        const assertion = JSON.parse(String(body.authenticationResponseJson || "{}"));
        const deviceId = String(body.deviceId || "");
        const deviceLabel = String(body.deviceLabel || "Android Device").slice(0, 80);

        const chRef = db.collection("webauthn_challenges").doc(challengeId);
        const chSnap = await chRef.get();
        const chData = chSnap.data();
        if (!chData || chData.expiresAt.toMillis() < Date.now()) {
          throw Object.assign(new Error("Passkey authentication challenge expired."), { statusCode: 400, code: "CHALLENGE_EXPIRED" });
        }

        const credId = String(assertion.id || "");
        const matchQuery = await db.collectionGroup("passkeys").where("credentialId", "==", credId).limit(1).get();
        if (matchQuery.empty) {
          throw Object.assign(new Error("Passkey credential not recognized."), { statusCode: 401, code: "PASSKEY_NOT_FOUND" });
        }
        const passkeyDoc = matchQuery.docs[0];
        const pk = passkeyDoc.data();
        const uid = String(pk.userId);

        const verification = await verifyAuthenticationResponse({
          response: assertion,
          expectedChallenge: chData.challenge,
          expectedOrigin: EXPECTED_ORIGINS,
          expectedRPID: RP_ID,
          credential: {
            id: pk.credentialId,
            publicKey: new Uint8Array(Buffer.from(pk.publicKeyBase64Url, "base64url")),
            counter: Number(pk.counter || 0),
            transports: pk.transports || ["internal"],
          },
          requireUserVerification: true,
        });

        if (!verification.verified) {
          throw Object.assign(new Error("Passkey signature verification failed."), { statusCode: 401, code: "WEBAUTHN_VERIFICATION_FAILED" });
        }

        await chRef.delete();
        await passkeyDoc.ref.update({
          counter: verification.authenticationInfo.newCounter,
          lastUsedAt: FieldValue.serverTimestamp(),
        });

        const sessionId = crypto.randomUUID();
        await db.collection("users").doc(uid).collection("sessions").doc(sessionId).set({
          sessionId,
          userId: uid,
          deviceId: deviceId || sessionId,
          deviceLabel,
          platform: "Android",
          authMethod: "PASSKEY",
          isTrustedDevice: true,
          revoked: false,
          createdAt: FieldValue.serverTimestamp(),
          lastActiveAt: FieldValue.serverTimestamp(),
        });
        if (deviceId) {
          await db.collection("users").doc(uid).collection("trusted_devices").doc(deviceId).set({
            deviceId,
            deviceLabel,
            lastVerifiedAt: FieldValue.serverTimestamp(),
          }, { merge: true });
        }

        const customToken = await admin.auth().createCustomToken(uid, { authMethod: "PASSKEY", sessionId });
        res.json({ ok: true, uid, customToken, sessionId });
        return;
      }

      case "revokeAllSessions": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const keepSessionId = typeof body.keepSessionId === "string" ? body.keepSessionId : null;
        await admin.auth().revokeRefreshTokens(uid);
        const snap = await db.collection("users").doc(uid).collection("sessions").where("revoked", "==", false).get();
        const batch = db.batch();
        snap.docs.forEach((doc) => {
          if (doc.id !== keepSessionId) {
            batch.update(doc.ref, { revoked: true, revokedAt: FieldValue.serverTimestamp() });
          }
        });
        await batch.commit();
        res.json({ ok: true, revokedCount: snap.size });
        return;
      }

      case "joinFamilyByInviteCode": {
        res.status(410).json({
          ok: false,
          code: "INVITE_CODE_DEPRECATED",
          message: "Static invite codes are disabled. Use an expiring invitation token requiring Family Admin approval.",
        });
        return;
      }

      case "createFamily": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const name = String(body.name || "").trim();
        if (name.length < 2 || name.length > 60) {
          throw Object.assign(new Error("Family name must be between 2 and 60 characters."), { statusCode: 400, code: "INVALID_INPUT" });
        }
        const currencyCode = String(body.currencyCode || "BDT").trim().toUpperCase().slice(0, 6) || "BDT";
        const description = typeof body.description === "string" ? body.description.trim().slice(0, 200) : "";
        const familyPassword = String(body.familyPassword || "");
        const { saltHex, hashHex } = hashPasswordScrypt(familyPassword);
        const displayName = String(decoded.name || body.displayName || "Family Admin").trim().slice(0, 80) || "Family Admin";

        let createdFamilyId = "";
        for (let attempt = 0; attempt < 5; attempt++) {
          const candidateId = generateRandomFamilyId();
          const famRef = db.collection("families").doc(candidateId);
          const idIndexRef = db.collection("family_id_index").doc(candidateId);
          const secretRef = db.collection("family_secrets").doc(candidateId);
          const memberRef = famRef.collection("members").doc(uid);

          try {
            await db.runTransaction(async (tx) => {
              const idIndexSnap = await tx.get(idIndexRef);
              const existingFamSnap = await tx.get(famRef);
              if (idIndexSnap.exists || existingFamSnap.exists) {
                throw Object.assign(new Error("Family ID collision"), { code: "COLLISION" });
              }
              const userSnap = await tx.get(db.collection("users").doc(uid));
              const userData = userSnap.data() || {};
              const rawPhone = String(userData.phoneNumber || decoded.phone_number || "");
              const memberId = rawPhone || uid;
              const maskedPhone = rawPhone ? maskPhoneNumber(rawPhone) : null;

              tx.set(idIndexRef, {
                familyId: candidateId,
                ownerId: uid,
                createdAt: FieldValue.serverTimestamp(),
              });
              tx.set(secretRef, {
                familyId: candidateId,
                passwordSalt: saltHex,
                passwordHash: hashHex,
                algorithm: "scrypt-N16384-r8-p1",
                updatedByUid: uid,
                createdAt: FieldValue.serverTimestamp(),
                updatedAt: FieldValue.serverTimestamp(),
              });
              tx.set(famRef, {
                name,
                ownerId: uid,
                createdByUserId: uid,
                currencyCode,
                memberUids: [uid],
                adminUids: [uid],
                suspendedUids: [],
                isPersonal: false,
                ...(description ? { description } : {}),
                requireAdminApproval: true,
                memberChatEnabled: true,
                membersCanAddExpenses: true,
                budgetStartDay: 1,
                createdAt: FieldValue.serverTimestamp(),
                updatedAt: FieldValue.serverTimestamp(),
              });
              const cardMemberId = generateMemberCardId16();
              tx.set(memberRef, {
                userId: uid,
                familyId: candidateId,
                displayName,
                memberId,
                cardMemberId,
                ...(maskedPhone ? { maskedPhone } : {}),
                memberStatus: "ACTIVE",
                role: "ADMIN",
                memberUids: [uid],
                adminUids: [uid],
                relationLabel: "Family Admin",
                canCreateRecords: true,
                canViewSharedFinances: true,
                canParticipateInChat: true,
                joinedAt: FieldValue.serverTimestamp(),
                updatedAt: FieldValue.serverTimestamp(),
              });
              tx.set(famRef.collection("member_cards").doc(uid), {
                userId: uid,
                familyId: candidateId,
                cardMemberId,
                familyName: name,
                displayName,
                ...(userData.photoUrl ? { photoUrl: String(userData.photoUrl).slice(0, 500) } : {}),
                role: "ADMIN",
                memberStatus: "ACTIVE",
                memberUids: [uid],
                adminUids: [uid],
                showAvailableBalance: true,
                showIncomeAndExpense: true,
                showFamilyContributions: true,
                showLoans: true,
                showSavings: true,
                showInvestments: true,
                showTransactionHistory: true,
                allowAdminDetailedAudit: true,
                joinedAt: FieldValue.serverTimestamp(),
                updatedAt: FieldValue.serverTimestamp(),
              });
              tx.set(
                db.collection("users").doc(uid),
                {
                  activeFamilyId: candidateId,
                  updatedAt: FieldValue.serverTimestamp(),
                },
                { merge: true }
              );
              writeAuditLogInTx(
                tx,
                candidateId,
                uid,
                "families",
                candidateId,
                "CREATE",
                `Created family workspace "${name}" (${candidateId}) with single Primary Admin`,
                [uid]
              );
            });
            createdFamilyId = candidateId;
            break;
          } catch (e: any) {
            if (e?.code !== "COLLISION" || attempt === 4) {
              throw e;
            }
          }
        }

        res.json({ ok: true, familyId: createdFamilyId, name, ownerId: uid });
        return;
      }

      case "verifyFamilyAdminLogin": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const familyPassword = String(body.familyPassword || "");
        if (!familyId || !familyPassword) {
          throw Object.assign(new Error("Family ID and Family Password are required."), { statusCode: 400, code: "INVALID_INPUT" });
        }

        await enforceRateLimit(`fam_admin_${familyId}_${uid}`, 5, 15 * 60 * 1000, 15 * 60 * 1000);

        const { fam } = await assertFamilyAdmin(familyId, uid);
        const secretSnap = await db.collection("family_secrets").doc(familyId).get();
        if (!secretSnap.exists) {
          throw Object.assign(new Error("Family Admin password credentials are not configured."), { statusCode: 404, code: "FAMILY_SECRET_MISSING" });
        }
        const sec = secretSnap.data()!;
        const verified = verifyPasswordScrypt(familyPassword, sec.passwordSalt, sec.passwordHash);
        if (!verified) {
          throw Object.assign(new Error("Invalid Family Admin password."), { statusCode: 401, code: "INVALID_FAMILY_PASSWORD" });
        }

        await clearRateLimit(`fam_admin_${familyId}_${uid}`);
        const verifiedUntilMillis = Date.now() + 30 * 60 * 1000;

        await db.runTransaction(async (tx) => {
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [uid];
          writeAuditLogInTx(
            tx,
            familyId,
            uid,
            "families",
            familyId,
            "STATUS_CHANGE",
            "Verified Family Admin login session",
            memberUids
          );
        });

        res.json({
          ok: true,
          familyId,
          familyName: String(fam.name || "Family Workspace"),
          verifiedUntilMillis,
        });
        return;
      }

      case "requestJoinByFamilyPassword": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const familyPassword = String(body.familyPassword || "");
        const relationLabel = String(body.relationLabel || "Family Member").trim().slice(0, 40) || "Family Member";
        if (!isValidFamilyId12(familyId) && !/^fam_[a-zA-Z0-9_-]{8,64}$/i.test(familyId)) {
          throw Object.assign(new Error("Enter a valid 12-character Family ID."), { statusCode: 400, code: "INVALID_FAMILY_ID" });
        }
        if (!familyPassword) {
          throw Object.assign(new Error("Family Password is required."), { statusCode: 400, code: "INVALID_INPUT" });
        }

        await enforceRateLimit(`fam_join_${familyId}_${uid}`, 5, 15 * 60 * 1000, 15 * 60 * 1000);

        const [famSnap, secretSnap, userSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("family_secrets").doc(familyId).get(),
          db.collection("users").doc(uid).get(),
        ]);

        if (!famSnap.exists || !secretSnap.exists) {
          throw Object.assign(new Error("Invalid Family ID or Family Password."), { statusCode: 401, code: "INVALID_FAMILY_CREDENTIALS" });
        }

        const sec = secretSnap.data()!;
        if (!verifyPasswordScrypt(familyPassword, sec.passwordSalt, sec.passwordHash)) {
          throw Object.assign(new Error("Invalid Family ID or Family Password."), { statusCode: 401, code: "INVALID_FAMILY_CREDENTIALS" });
        }

        const fam = famSnap.data()!;
        const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
        const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];

        if (suspendedUids.includes(uid)) {
          throw Object.assign(new Error("Your access to this family was suspended by the Family Admin."), {
            statusCode: 403,
            code: "MEMBER_SUSPENDED",
          });
        }
        if (memberUids.includes(uid)) {
          throw Object.assign(new Error("You are already a member of this family."), { statusCode: 409, code: "ALREADY_MEMBER" });
        }
        if (memberUids.length >= 20) {
          throw Object.assign(new Error("Family has reached maximum member capacity (20)."), { statusCode: 409, code: "FAMILY_FULL" });
        }

        await clearRateLimit(`fam_join_${familyId}_${uid}`);

        const userData = userSnap.data() || {};
        const displayName = String(userData.displayName || decoded.name || body.displayName || "Family Member").trim().slice(0, 80);
        const rawPhone = String(userData.phoneNumber || decoded.phone_number || "");
        const memberId = rawPhone || uid;
        const maskedPhone = rawPhone ? maskPhoneNumber(rawPhone) : null;
        const contactSummary = maskedPhone || String(decoded.email || uid.slice(0, 12)).slice(0, 120);

        await db.runTransaction(async (tx) => {
          tx.set(db.collection("families").doc(familyId).collection("join_requests").doc(uid), {
            userId: uid,
            familyId,
            displayName,
            memberId,
            ...(maskedPhone ? { maskedPhone } : {}),
            contactSummary,
            relationLabel,
            authMethod: "FAMILY_PASSWORD",
            invitationId: "fam_pwd_join",
            tokenHash: "0".repeat(64),
            requestedRole: "MEMBER",
            status: "PENDING_APPROVAL",
            requestedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          writeNotificationInTx(
            tx,
            familyId,
            String(fam.ownerId),
            uid,
            "New Family Join Request",
            `${displayName} requested to join ${String(fam.name || "your family")} (${relationLabel}). Review in Family Approvals.`,
            "FAMILY_JOIN_REQUEST",
            "FAMILY_MANAGEMENT",
            uid,
            memberUids
          );
          writeAuditLogInTx(
            tx,
            familyId,
            uid,
            "join_requests",
            uid,
            "CREATE",
            `${displayName} submitted a Family ID + Password join request pending Admin approval`,
            memberUids
          );
        });

        res.json({
          ok: true,
          familyId,
          familyName: String(fam.name || "Family"),
          status: "PENDING_APPROVAL",
        });
        return;
      }

      case "changeFamilyPassword": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const currentFamilyPassword = String(body.currentFamilyPassword || "");
        const newFamilyPassword = String(body.newFamilyPassword || "");

        const { fam } = await assertFamilyAdmin(familyId, uid);
        validatePasswordStrength(newFamilyPassword);
        await enforceRateLimit(`fam_pwd_chg_${familyId}_${uid}`, 5, 15 * 60 * 1000, 15 * 60 * 1000);

        const secretRef = db.collection("family_secrets").doc(familyId);
        const secretSnap = await secretRef.get();
        if (secretSnap.exists) {
          const sec = secretSnap.data()!;
          if (!verifyPasswordScrypt(currentFamilyPassword, sec.passwordSalt, sec.passwordHash)) {
            throw Object.assign(new Error("Current Family Password is incorrect."), {
              statusCode: 401,
              code: "INVALID_FAMILY_PASSWORD",
            });
          }
        }
        await clearRateLimit(`fam_pwd_chg_${familyId}_${uid}`);

        const { saltHex, hashHex } = hashPasswordScrypt(newFamilyPassword);
        await db.runTransaction(async (tx) => {
          tx.set(
            secretRef,
            {
              familyId,
              passwordSalt: saltHex,
              passwordHash: hashHex,
              algorithm: "scrypt-N16384-r8-p1",
              updatedByUid: uid,
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [uid];
          writeAuditLogInTx(
            tx,
            familyId,
            uid,
            "families",
            familyId,
            "STATUS_CHANGE",
            "Family Admin rotated the Family Password",
            memberUids
          );
        });

        res.json({ ok: true, familyId });
        return;
      }

      case "setMemberSuspension": {
        const decoded = await verifyBearerUser(req);
        const adminUid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const targetUid = String(body.targetUid || "").trim();
        const suspend = body.suspend === true;

        await assertFamilyAdmin(familyId, adminUid);
        const famRef = db.collection("families").doc(familyId);

        await db.runTransaction(async (tx) => {
          const famSnap = await tx.get(famRef);
          if (!famSnap.exists) {
            throw Object.assign(new Error("Family not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
          }
          const fam = famSnap.data()!;
          if (targetUid === fam.ownerId) {
            throw Object.assign(new Error("Primary Family Admin cannot be suspended."), {
              statusCode: 400,
              code: "CANNOT_SUSPEND_OWNER",
            });
          }
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
          const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [fam.ownerId];
          const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];
          const memberRef = famRef.collection("members").doc(targetUid);
          const memberSnap = await tx.get(memberRef);
          if (!memberSnap.exists) {
            throw Object.assign(new Error("Family member record not found."), { statusCode: 404, code: "MEMBER_NOT_FOUND" });
          }

          let nextMembers: string[];
          let nextAdmins: string[];
          let nextSuspended: string[];
          if (suspend) {
            nextMembers = memberUids.filter((id) => id !== targetUid);
            nextAdmins = adminUids.filter((id) => id !== targetUid);
            nextSuspended = Array.from(new Set([...suspendedUids, targetUid]));
          } else {
            if (!memberUids.includes(targetUid) && memberUids.length >= 20) {
              throw Object.assign(new Error("Family has reached maximum member capacity (20)."), {
                statusCode: 409,
                code: "FAMILY_FULL",
              });
            }
            nextMembers = Array.from(new Set([...memberUids, targetUid]));
            nextAdmins = adminUids;
            nextSuspended = suspendedUids.filter((id) => id !== targetUid);
          }

          tx.update(famRef, {
            memberUids: nextMembers,
            adminUids: nextAdmins,
            suspendedUids: nextSuspended,
            updatedAt: FieldValue.serverTimestamp(),
          });
          await syncDenormalizedMemberLists(tx, famRef, nextMembers, nextAdmins, targetUid);

          tx.update(memberRef, {
            role: "MEMBER",
            memberStatus: suspend ? "SUSPENDED" : "ACTIVE",
            memberUids: nextMembers,
            adminUids: nextAdmins,
            canCreateRecords: suspend ? false : fam.membersCanAddExpenses !== false,
            canViewSharedFinances: !suspend,
            canParticipateInChat: suspend ? false : fam.memberChatEnabled !== false,
            updatedAt: FieldValue.serverTimestamp(),
          });
          const cardSnap = await tx.get(famRef.collection("member_cards").doc(targetUid));
          if (cardSnap.exists) {
            tx.update(famRef.collection("member_cards").doc(targetUid), {
              role: "MEMBER",
              memberStatus: suspend ? "SUSPENDED" : "ACTIVE",
              memberUids: nextMembers,
              adminUids: nextAdmins,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }

          tx.set(
            db.collection("users").doc(targetUid).collection("membership_notices").doc(familyId),
            {
              familyId,
              familyName: String(fam.name || "Family Workspace"),
              status: suspend ? "SUSPENDED" : "ACTIVE",
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

          if (suspend) {
            tx.set(
              db.collection("users").doc(targetUid),
              { activeFamilyId: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() },
              { merge: true }
            );
          }

          writeAuditLogInTx(
            tx,
            familyId,
            adminUid,
            "members",
            targetUid,
            "MEMBER_CHANGE",
            suspend ? `Suspended family access for ${targetUid}` : `Reinstated family access for ${targetUid}`,
            nextMembers
          );
        });

        res.json({ ok: true, familyId, targetUid, suspended: suspend });
        return;
      }

      case "createFamilyInvitation": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const targetRole = body.targetRole === "ADMIN" ? "ADMIN" : "MEMBER";
        const inviteeHint = typeof body.inviteeHint === "string" ? body.inviteeHint.trim().slice(0, 80) : "";
        const expiresInHours = Math.min(168, Math.max(1, Number(body.expiresInHours || 48)));
        const maxUses = Math.min(10, Math.max(1, Number(body.maxUses || 1)));

        const { fam } = await assertFamilyAdmin(familyId, uid);
        const { token, tokenHash, tokenPreview } = generateInvitationToken();
        const invitationId = `inv_${crypto.randomBytes(8).toString("hex")}`;
        const expiresAt = Timestamp.fromMillis(Date.now() + expiresInHours * 60 * 60 * 1000);

        const batch = db.batch();
        batch.set(db.collection("family_invites").doc(tokenHash), {
          tokenHash,
          invitationId,
          familyId,
          familyName: fam.name,
          createdByUid: uid,
          targetRole,
          ...(inviteeHint ? { inviteeHint } : {}),
          status: "ACTIVE",
          maxUses,
          usedCount: 0,
          expiresAt,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        });
        batch.set(db.collection("families").doc(familyId).collection("invitations").doc(invitationId), {
          invitationId,
          familyId,
          tokenHash,
          token,
          tokenPreview,
          targetRole,
          ...(inviteeHint ? { inviteeHint } : {}),
          status: "ACTIVE",
          maxUses,
          usedCount: 0,
          createdByUid: uid,
          expiresAt,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        });
        await batch.commit();

        res.json({
          ok: true,
          invitationId,
          token,
          tokenPreview,
          expiresAtMillis: expiresAt.toMillis(),
        });
        return;
      }

      case "revokeFamilyInvitation": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const invitationId = String(body.invitationId || "").trim();
        await assertFamilyAdmin(familyId, uid);

        const invRef = db.collection("families").doc(familyId).collection("invitations").doc(invitationId);
        const invSnap = await invRef.get();
        if (!invSnap.exists) {
          throw Object.assign(new Error("Invitation not found."), { statusCode: 404, code: "INVITATION_NOT_FOUND" });
        }
        const tokenHash = String(invSnap.data()?.tokenHash || "");
        const batch = db.batch();
        batch.update(invRef, {
          status: "REVOKED",
          token: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp(),
        });
        if (tokenHash) {
          batch.update(db.collection("family_invites").doc(tokenHash), {
            status: "REVOKED",
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
        await batch.commit();
        res.json({ ok: true, invitationId });
        return;
      }

      case "requestJoinWithInvitation": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const tokenHash = hashInvitationToken(String(body.invitationToken || ""));
        const relationLabel = String(body.relationLabel || "Family Member").trim().slice(0, 40) || "Family Member";
        const displayName = String(decoded.name || body.displayName || decoded.phone_number || "Family Member").trim().slice(0, 80);
        const contactSummary = String(decoded.phone_number || decoded.email || uid.slice(0, 12)).slice(0, 120);

        const inviteSnap = await db.collection("family_invites").doc(tokenHash).get();
        if (!inviteSnap.exists) {
          throw Object.assign(new Error("Invitation token is invalid or unrecognized."), { statusCode: 404, code: "INVITE_INVALID" });
        }
        const invite = inviteSnap.data()!;
        if (invite.status !== "ACTIVE") {
          throw Object.assign(new Error(`Invitation is ${String(invite.status).toLowerCase()}.`), { statusCode: 403, code: "INVITE_INACTIVE" });
        }
        if (invite.expiresAt.toMillis() <= Date.now()) {
          throw Object.assign(new Error("Invitation token has expired. Ask your Family Admin for a new one."), { statusCode: 410, code: "INVITE_EXPIRED" });
        }
        if (Number(invite.usedCount || 0) >= Number(invite.maxUses || 1)) {
          throw Object.assign(new Error("Invitation token usage limit has been reached."), { statusCode: 409, code: "INVITE_EXHAUSTED" });
        }

        const familyId = String(invite.familyId);
        const famSnap = await db.collection("families").doc(familyId).get();
        if (!famSnap.exists) {
          throw Object.assign(new Error("Target family workspace no longer exists."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
        if (memberUids.includes(uid)) {
          throw Object.assign(new Error("You are already a member of this family."), { statusCode: 409, code: "ALREADY_MEMBER" });
        }
        if (memberUids.length >= 20) {
          throw Object.assign(new Error("Family has reached maximum member capacity (20)."), { statusCode: 409, code: "FAMILY_FULL" });
        }

        await db.collection("families").doc(familyId).collection("join_requests").doc(uid).set({
          userId: uid,
          familyId,
          displayName,
          contactSummary,
          relationLabel,
          invitationId: invite.invitationId,
          tokenHash,
          requestedRole: invite.targetRole === "ADMIN" ? "ADMIN" : "MEMBER",
          status: "PENDING_APPROVAL",
          requestedAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        });

        res.json({
          ok: true,
          familyId,
          familyName: String(fam.name || invite.familyName || "Family"),
          status: "PENDING_APPROVAL",
        });
        return;
      }

      case "reviewJoinRequest": {
        const decoded = await verifyBearerUser(req);
        const adminUid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const requesterUid = String(body.requesterUid || "").trim();
        const approve = Boolean(body.approve);

        await assertFamilyAdmin(familyId, adminUid);

        const famRef = db.collection("families").doc(familyId);
        const reqRef = famRef.collection("join_requests").doc(requesterUid);

        await db.runTransaction(async (tx) => {
          const reqSnap = await tx.get(reqRef);
          if (!reqSnap.exists) {
            throw Object.assign(new Error("Join request not found."), { statusCode: 404, code: "REQUEST_NOT_FOUND" });
          }
          const reqData = reqSnap.data()!;
          if (reqData.status !== "PENDING_APPROVAL") {
            throw Object.assign(new Error("Join request has already been reviewed."), { statusCode: 409, code: "ALREADY_REVIEWED" });
          }

          const famSnap = await tx.get(famRef);
          const fam = famSnap.data()!;
          const currentMembers: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
          const currentAdmins: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [fam.ownerId];
          const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];

          if (!approve) {
            tx.update(reqRef, {
              status: "REJECTED",
              reviewedByUid: adminUid,
              reviewedAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
            writeAuditLogInTx(
              tx,
              familyId,
              adminUid,
              "join_requests",
              requesterUid,
              "STATUS_CHANGE",
              `Rejected join request from ${String(reqData.displayName || requesterUid)}`,
              currentMembers
            );
            return;
          }

          if (currentMembers.length >= 20 && !currentMembers.includes(requesterUid)) {
            throw Object.assign(new Error("Family has reached maximum member capacity (20)."), { statusCode: 409, code: "FAMILY_FULL" });
          }

          const reqUserSnap = await tx.get(db.collection("users").doc(requesterUid));
          const reqUser = reqUserSnap.data() || {};
          const rawPhone = String(reqUser.phoneNumber || reqData.memberId || "");
          const memberId = rawPhone || requesterUid;
          const maskedPhone = reqData.maskedPhone ? String(reqData.maskedPhone) : rawPhone ? maskPhoneNumber(rawPhone) : null;

          // Maintain single Primary Admin unless ownership is explicitly transferred
          const role = "MEMBER";
          const nextMembers = Array.from(new Set([...currentMembers, requesterUid]));
          const nextAdmins = currentAdmins.length > 0 ? currentAdmins : [fam.ownerId];
          const nextSuspended = suspendedUids.filter((id) => id !== requesterUid);

          tx.update(famRef, {
            memberUids: nextMembers,
            adminUids: nextAdmins,
            suspendedUids: nextSuspended,
            updatedAt: FieldValue.serverTimestamp(),
          });

          await syncDenormalizedMemberLists(tx, famRef, nextMembers, nextAdmins, requesterUid);

          const requesterCardMemberId = generateMemberCardId16();
          tx.set(famRef.collection("members").doc(requesterUid), {
            userId: requesterUid,
            familyId,
            displayName: String(reqData.displayName || "Family Member"),
            memberId,
            cardMemberId: requesterCardMemberId,
            ...(maskedPhone ? { maskedPhone } : {}),
            memberStatus: "ACTIVE",
            role,
            memberUids: nextMembers,
            adminUids: nextAdmins,
            relationLabel: String(reqData.relationLabel || "Member"),
            canCreateRecords: fam.membersCanAddExpenses !== false,
            canViewSharedFinances: true,
            canParticipateInChat: fam.memberChatEnabled !== false,
            joinedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(famRef.collection("member_cards").doc(requesterUid), {
            userId: requesterUid,
            familyId,
            cardMemberId: requesterCardMemberId,
            familyName: String(fam.name || "Family Workspace"),
            displayName: String(reqData.displayName || "Family Member"),
            ...(reqUser.photoUrl ? { photoUrl: String(reqUser.photoUrl).slice(0, 500) } : {}),
            role,
            memberStatus: "ACTIVE",
            memberUids: nextMembers,
            adminUids: nextAdmins,
            showAvailableBalance: true,
            showIncomeAndExpense: true,
            showFamilyContributions: true,
            showLoans: true,
            showSavings: true,
            showInvestments: true,
            showTransactionHistory: true,
            allowAdminDetailedAudit: true,
            joinedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });

          tx.update(reqRef, {
            status: "APPROVED",
            reviewedByUid: adminUid,
            reviewedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });

          const tokenHash = String(reqData.tokenHash || "");
          const invitationId = String(reqData.invitationId || "");
          if (tokenHash && reqData.authMethod !== "FAMILY_PASSWORD" && invitationId !== "fam_pwd_join") {
            const lookupRef = db.collection("family_invites").doc(tokenHash);
            const lookupSnap = await tx.get(lookupRef);
            if (lookupSnap.exists) {
              const l = lookupSnap.data()!;
              const nextUsed = Number(l.usedCount || 0) + 1;
              const nextStatus = nextUsed >= Number(l.maxUses || 1) ? "USED" : "ACTIVE";
              tx.update(lookupRef, {
                usedCount: nextUsed,
                status: nextStatus,
                updatedAt: FieldValue.serverTimestamp(),
              });
              if (invitationId) {
                tx.update(famRef.collection("invitations").doc(invitationId), {
                  usedCount: nextUsed,
                  status: nextStatus,
                  updatedAt: FieldValue.serverTimestamp(),
                });
              }
            }
          }

          tx.set(db.collection("users").doc(requesterUid), {
            activeFamilyId: familyId,
            updatedAt: FieldValue.serverTimestamp(),
          }, { merge: true });

          tx.set(
            db.collection("users").doc(requesterUid).collection("membership_notices").doc(familyId),
            {
              familyId,
              familyName: String(fam.name || "Family Workspace"),
              status: "ACTIVE",
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

          writeNotificationInTx(
            tx,
            familyId,
            requesterUid,
            adminUid,
            "Family Request Approved",
            `Welcome! You have been approved to join ${String(fam.name || "Family Workspace")}.`,
            "INVITATION_APPROVED",
            "FAMILY_MANAGEMENT",
            familyId,
            nextMembers
          );
          writeAuditLogInTx(
            tx,
            familyId,
            adminUid,
            "members",
            requesterUid,
            "MEMBER_CHANGE",
            `Approved join request for ${String(reqData.displayName || requesterUid)}`,
            nextMembers
          );
        });

        res.json({ ok: true, familyId, requesterUid, approved: approve });
        return;
      }

      case "updateMemberRole": {
        const decoded = await verifyBearerUser(req);
        const adminUid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const targetUid = String(body.targetUid || "").trim();
        const newRole = body.role === "ADMIN" ? "ADMIN" : "MEMBER";
        await assertFamilyAdmin(familyId, adminUid);

        const famRef = db.collection("families").doc(familyId);
        await db.runTransaction(async (tx) => {
          const famSnap = await tx.get(famRef);
          const fam = famSnap.data()!;
          if (newRole === "ADMIN" && targetUid !== fam.ownerId) {
            throw Object.assign(
              new Error("Only one Primary Family Admin is permitted. Use Transfer Family Ownership instead."),
              { statusCode: 400, code: "SINGLE_ADMIN_ENFORCED" }
            );
          }
          if (targetUid === fam.ownerId && newRole !== "ADMIN") {
            throw Object.assign(new Error("Transfer Family Ownership before demoting the primary Family Admin."), { statusCode: 400, code: "CANNOT_DEMOTE_OWNER" });
          }
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
          if (!memberUids.includes(targetUid)) {
            throw Object.assign(new Error("Target user is not a member of this family."), { statusCode: 404, code: "MEMBER_NOT_FOUND" });
          }
          const nextAdmins = [fam.ownerId];
          tx.update(famRef, { adminUids: nextAdmins, updatedAt: FieldValue.serverTimestamp() });
          await syncDenormalizedMemberLists(tx, famRef, memberUids, nextAdmins);
          tx.update(famRef.collection("members").doc(targetUid), {
            role: newRole,
            adminUids: nextAdmins,
            updatedAt: FieldValue.serverTimestamp(),
          });
        });

        res.json({ ok: true, familyId, targetUid, role: newRole });
        return;
      }

      case "removeFamilyMember": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const targetUid = String(body.targetUid || "").trim();

        const famRef = db.collection("families").doc(familyId);
        await db.runTransaction(async (tx) => {
          const famSnap = await tx.get(famRef);
          if (!famSnap.exists) {
            throw Object.assign(new Error("Family not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
          }
          const fam = famSnap.data()!;
          const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [fam.ownerId];
          const isAdmin = fam.ownerId === callerUid || adminUids.includes(callerUid);
          if (!isAdmin && callerUid !== targetUid) {
            throw Object.assign(new Error("Only a Family Admin can remove another member."), { statusCode: 403, code: "ADMIN_REQUIRED" });
          }
          if (targetUid === fam.ownerId) {
            throw Object.assign(new Error("Primary Family Admin cannot be removed. Transfer Family Ownership first."), { statusCode: 400, code: "CANNOT_REMOVE_OWNER" });
          }
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
          const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];
          const nextMembers = memberUids.filter((id) => id !== targetUid);
          const nextAdmins = adminUids.filter((id) => id !== targetUid);
          const nextSuspended = suspendedUids.filter((id) => id !== targetUid);

          tx.update(famRef, {
            memberUids: nextMembers,
            adminUids: nextAdmins,
            suspendedUids: nextSuspended,
            updatedAt: FieldValue.serverTimestamp(),
          });
          await syncDenormalizedMemberLists(tx, famRef, nextMembers, nextAdmins, targetUid);
          tx.delete(famRef.collection("members").doc(targetUid));

          if (callerUid !== targetUid) {
            tx.set(
              db.collection("users").doc(targetUid).collection("membership_notices").doc(familyId),
              {
                familyId,
                familyName: String(fam.name || "Family Workspace"),
                status: "REMOVED",
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true }
            );
          } else {
            tx.delete(db.collection("users").doc(targetUid).collection("membership_notices").doc(familyId));
          }

          tx.set(
            db.collection("users").doc(targetUid),
            { activeFamilyId: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() },
            { merge: true }
          );

          writeAuditLogInTx(
            tx,
            familyId,
            callerUid,
            "members",
            targetUid,
            "MEMBER_CHANGE",
            callerUid === targetUid ? `Member ${targetUid} left the family` : `Admin removed member ${targetUid}`,
            nextMembers
          );
        });

        res.json({ ok: true, familyId, removedUid: targetUid });
        return;
      }

      case "transferFamilyOwnership": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = normalizeFamilyIdInput(String(body.familyId || ""));
        const newOwnerUid = String(body.newOwnerUid || "").trim();
        const familyPassword = String(body.familyPassword || "");

        await enforceRateLimit(`fam_transfer_${familyId}_${callerUid}`, 5, 15 * 60 * 1000, 15 * 60 * 1000);
        const secretSnap = await db.collection("family_secrets").doc(familyId).get();
        if (secretSnap.exists) {
          const sec = secretSnap.data()!;
          if (!familyPassword || !verifyPasswordScrypt(familyPassword, sec.passwordSalt, sec.passwordHash)) {
            throw Object.assign(new Error("Family Password confirmation failed."), {
              statusCode: 401,
              code: "INVALID_FAMILY_PASSWORD",
            });
          }
        }
        await clearRateLimit(`fam_transfer_${familyId}_${callerUid}`);

        const famRef = db.collection("families").doc(familyId);
        await db.runTransaction(async (tx) => {
          const famSnap = await tx.get(famRef);
          if (!famSnap.exists) {
            throw Object.assign(new Error("Family not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
          }
          const fam = famSnap.data()!;
          if (fam.ownerId !== callerUid) {
            throw Object.assign(new Error("Only the current primary Family Admin can transfer ownership."), { statusCode: 403, code: "OWNER_REQUIRED" });
          }
          if (newOwnerUid === callerUid) {
            throw Object.assign(new Error("Select another member to transfer ownership to."), { statusCode: 400, code: "INVALID_TARGET" });
          }
          const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
          if (!memberUids.includes(newOwnerUid)) {
            throw Object.assign(new Error("Target user must already be an approved family member."), { statusCode: 404, code: "MEMBER_NOT_FOUND" });
          }
          // Enforce single Primary Family Admin on transfer
          const nextAdmins = [newOwnerUid];

          tx.update(famRef, {
            ownerId: newOwnerUid,
            adminUids: nextAdmins,
            updatedAt: FieldValue.serverTimestamp(),
          });
          await syncDenormalizedMemberLists(tx, famRef, memberUids, nextAdmins);
          tx.update(famRef.collection("members").doc(newOwnerUid), {
            role: "ADMIN",
            adminUids: nextAdmins,
            canCreateRecords: true,
            canViewSharedFinances: true,
            canParticipateInChat: true,
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.update(famRef.collection("members").doc(callerUid), {
            role: "MEMBER",
            adminUids: nextAdmins,
            updatedAt: FieldValue.serverTimestamp(),
          });

          writeNotificationInTx(
            tx,
            familyId,
            newOwnerUid,
            callerUid,
            "Primary Admin Ownership Transferred",
            `You are now the Primary Family Admin for ${String(fam.name || "Family Workspace")}.`,
            "ADMIN_TRANSFER",
            "FAMILY_MANAGEMENT",
            familyId,
            memberUids
          );
          writeAuditLogInTx(
            tx,
            familyId,
            callerUid,
            "families",
            familyId,
            "MEMBER_CHANGE",
            `Transferred Primary Family Admin ownership to ${newOwnerUid}`,
            memberUids
          );
        });

        res.json({ ok: true, familyId, newOwnerUid });
        return;
      }

      case "updateFamilySettings": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const { famRef } = await assertFamilyAdmin(familyId, uid);

        const name = String(body.name || "").trim();
        if (name.length < 2 || name.length > 60) {
          throw Object.assign(new Error("Family name must be between 2 and 60 characters."), { statusCode: 400, code: "INVALID_INPUT" });
        }
        const currencyCode = String(body.currencyCode || "USD").trim().toUpperCase().slice(0, 6) || "USD";
        const description = typeof body.description === "string" ? body.description.trim().slice(0, 200) : "";
        const memberChatEnabled = body.memberChatEnabled !== false;
        const membersCanAddExpenses = body.membersCanAddExpenses !== false;
        const budgetStartDay = Math.min(28, Math.max(1, Number(body.budgetStartDay || 1)));

        await famRef.update({
          name,
          currencyCode,
          description: description || FieldValue.delete(),
          requireAdminApproval: true,
          memberChatEnabled,
          membersCanAddExpenses,
          budgetStartDay,
          updatedAt: FieldValue.serverTimestamp(),
        });

        res.json({ ok: true, familyId });
        return;
      }

      case "recoverAdminFamilies": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const [ownerSnap, creatorSnap] = await Promise.all([
          db.collection("families").where("ownerId", "==", uid).get(),
          db.collection("families").where("createdByUserId", "==", uid).get(),
        ]);

        const byId = new Map<string, any>();
        for (const snap of [ownerSnap, creatorSnap]) {
          snap.docs.forEach((d) => {
            const data = d.data();
            byId.set(d.id, {
              familyId: d.id,
              name: String(data.name || "Family Workspace"),
              currencyCode: String(data.currencyCode || "USD"),
              ownerId: String(data.ownerId || ""),
              createdByUserId: String(data.createdByUserId || ""),
              memberCount: Array.isArray(data.memberUids) ? data.memberUids.length : 1,
              isCurrentOwner: data.ownerId === uid,
              isOriginalCreator: data.createdByUserId === uid,
            });
          });
        }

        res.json({ ok: true, families: Array.from(byId.values()) });
        return;
      }

      case "meet/mintToken": {
        const decoded = await verifyBearerUser(req);
        const uid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const meetingId = String(body.meetingId || "").trim();
        const participantName = String(body.displayName || decoded.name || "Family Member").trim().slice(0, 80) || "Family Member";
        if (!familyId || !meetingId) {
          throw Object.assign(new Error("familyId and meetingId are required."), { statusCode: 400, code: "INVALID_INPUT" });
        }

        const [famSnap, meetingSnap, partSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("meetings").doc(meetingId).get(),
          db.collection("meetings").doc(meetingId).collection("participants").doc(uid).get(),
        ]);
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        if (!meetingSnap.exists) {
          throw Object.assign(new Error("Meeting not found."), { statusCode: 404, code: "MEETING_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const meeting = meetingSnap.data()!;
        const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
        const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];

        if (!memberUids.includes(uid)) {
          throw Object.assign(new Error("Only authorized family members can join this meeting."), { statusCode: 403, code: "FORBIDDEN_FAMILY" });
        }
        if (meeting.familyId !== familyId) {
          throw Object.assign(new Error("Meeting does not belong to this family."), { statusCode: 403, code: "CROSS_FAMILY_DENIED" });
        }
        if (meeting.status === "ENDED" || meeting.status === "CANCELLED") {
          throw Object.assign(new Error("This family meeting has already ended."), { statusCode: 410, code: "MEETING_ENDED" });
        }
        if (partSnap.exists && partSnap.data()?.removedByHost === true) {
          throw Object.assign(new Error("You were removed from this meeting by the host."), { statusCode: 403, code: "REMOVED_BY_HOST" });
        }

        const roomName = String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId));
        const isHostOrAdmin = meeting.hostUid === uid || fam.ownerId === uid || adminUids.includes(uid);
        const livekitUrl = String(process.env.LIVEKIT_URL || "").trim();
        const apiKey = String(process.env.LIVEKIT_API_KEY || "").trim();
        const apiSecret = String(process.env.LIVEKIT_API_SECRET || "").trim();

        const token = mintLiveKitAccessToken({
          apiKey,
          apiSecret,
          identity: uid,
          name: participantName,
          metadata: JSON.stringify({ familyId, meetingId, isHost: isHostOrAdmin }),
          ttlSeconds: 1800,
          grant: {
            room: roomName,
            roomJoin: true,
            roomCreate: isHostOrAdmin,
            roomAdmin: isHostOrAdmin,
            canPublish: true,
            canSubscribe: true,
            canPublishData: true,
          },
        });

        res.json({
          ok: true,
          token,
          livekitUrl,
          roomName,
          ttlSeconds: 1800,
          isHost: isHostOrAdmin,
        });
        return;
      }

      case "meet/muteParticipant": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const meetingId = String(body.meetingId || "").trim();
        const targetUid = String(body.targetUid || "").trim();
        const muted = body.muted !== false;
        const trackSid = typeof body.trackSid === "string" ? body.trackSid.trim() : "";

        const [famSnap, meetingSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("meetings").doc(meetingId).get(),
        ]);
        if (!famSnap.exists || !meetingSnap.exists) {
          throw Object.assign(new Error("Meeting or family not found."), { statusCode: 404, code: "NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const meeting = meetingSnap.data()!;
        if (meeting.familyId !== familyId) {
          throw Object.assign(new Error("Cross-family moderation rejected."), { statusCode: 403, code: "FORBIDDEN" });
        }
        assertMeetingHostOrAdmin(callerUid, String(meeting.hostUid || ""), fam);

        await db
          .collection("meetings")
          .doc(meetingId)
          .collection("participants")
          .doc(targetUid)
          .set(
            {
              participantUid: targetUid,
              meetingId,
              familyId,
              mutedByHost: muted,
              audioEnabled: !muted,
              memberUids: meeting.memberUids || fam.memberUids || [callerUid],
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

        let twirpWarning: string | undefined;
        if (trackSid) {
          const twirp = await callLiveKitRoomServiceTwirp({
            livekitUrl: String(process.env.LIVEKIT_URL || ""),
            apiKey: String(process.env.LIVEKIT_API_KEY || ""),
            apiSecret: String(process.env.LIVEKIT_API_SECRET || ""),
            rpcMethod: "MutePublishedTrack",
            room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
            body: {
              room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
              identity: targetUid,
              track_sid: trackSid,
              muted,
            },
          });
          twirpWarning = twirp.warning;
        }

        res.json({ ok: true, meetingId, targetUid, muted, warning: twirpWarning || null });
        return;
      }

      case "meet/removeParticipant": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const meetingId = String(body.meetingId || "").trim();
        const targetUid = String(body.targetUid || "").trim();

        const [famSnap, meetingSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("meetings").doc(meetingId).get(),
        ]);
        if (!famSnap.exists || !meetingSnap.exists) {
          throw Object.assign(new Error("Meeting or family not found."), { statusCode: 404, code: "NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const meeting = meetingSnap.data()!;
        if (meeting.familyId !== familyId) {
          throw Object.assign(new Error("Cross-family moderation rejected."), { statusCode: 403, code: "FORBIDDEN" });
        }
        assertMeetingHostOrAdmin(callerUid, String(meeting.hostUid || ""), fam);
        if (targetUid === meeting.hostUid) {
          throw Object.assign(new Error("Host cannot remove themselves; use End Meeting instead."), { statusCode: 400, code: "INVALID_TARGET" });
        }

        await db.runTransaction(async (tx) => {
          tx.update(db.collection("meetings").doc(meetingId), {
            activeParticipantUids: FieldValue.arrayRemove(targetUid),
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(
            db.collection("meetings").doc(meetingId).collection("participants").doc(targetUid),
            {
              participantUid: targetUid,
              meetingId,
              familyId,
              state: "REMOVED",
              removedByHost: true,
              audioEnabled: false,
              videoEnabled: false,
              screenSharing: false,
              leftAt: FieldValue.serverTimestamp(),
              memberUids: meeting.memberUids || fam.memberUids || [callerUid],
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
        });

        const twirp = await callLiveKitRoomServiceTwirp({
          livekitUrl: String(process.env.LIVEKIT_URL || ""),
          apiKey: String(process.env.LIVEKIT_API_KEY || ""),
          apiSecret: String(process.env.LIVEKIT_API_SECRET || ""),
          rpcMethod: "RemoveParticipant",
          room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
          body: {
            room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
            identity: targetUid,
          },
        });

        res.json({ ok: true, meetingId, targetUid, warning: twirp.warning || null });
        return;
      }

      case "meet/endMeeting": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const meetingId = String(body.meetingId || "").trim();

        const [famSnap, meetingSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("meetings").doc(meetingId).get(),
        ]);
        if (!famSnap.exists || !meetingSnap.exists) {
          throw Object.assign(new Error("Meeting or family not found."), { statusCode: 404, code: "NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const meeting = meetingSnap.data()!;
        if (meeting.familyId !== familyId) {
          throw Object.assign(new Error("Cross-family moderation rejected."), { statusCode: 403, code: "FORBIDDEN" });
        }
        assertMeetingHostOrAdmin(callerUid, String(meeting.hostUid || ""), fam);

        await db.collection("meetings").doc(meetingId).update({
          status: "ENDED",
          endedByUid: callerUid,
          endedAt: FieldValue.serverTimestamp(),
          activeParticipantUids: [],
          updatedAt: FieldValue.serverTimestamp(),
        });

        const twirp = await callLiveKitRoomServiceTwirp({
          livekitUrl: String(process.env.LIVEKIT_URL || ""),
          apiKey: String(process.env.LIVEKIT_API_KEY || ""),
          apiSecret: String(process.env.LIVEKIT_API_SECRET || ""),
          rpcMethod: "DeleteRoom",
          room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
          body: {
            room: String(meeting.roomName || buildCanonicalRoomName(familyId, meetingId)),
          },
        });

        res.json({ ok: true, meetingId, status: "ENDED", warning: twirp.warning || null });
        return;
      }

      case "meet/dispatchPush": {
        const decoded = await verifyBearerUser(req);
        const senderUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const targetUids: string[] = Array.isArray(body.targetUids)
          ? body.targetUids.map((u: unknown) => String(u).trim()).filter(Boolean)
          : [];
        const title = String(body.title || "Family Finance").trim().slice(0, 100);
        const messageBody = String(body.body || "").trim().slice(0, 400);
        const notificationType = String(body.notificationType || "GENERAL").trim().slice(0, 40);
        const deepLinkModule = String(body.deepLinkModule || "FAMILY_MEET").trim().slice(0, 40);
        const relatedRecordId = String(body.relatedRecordId || "").trim().slice(0, 80);

        const famSnap = await db.collection("families").doc(familyId).get();
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const memberUids: string[] = Array.isArray(famSnap.data()?.memberUids) ? famSnap.data()!.memberUids : [];
        if (!memberUids.includes(senderUid)) {
          throw Object.assign(new Error("Only family members can dispatch family push alerts."), { statusCode: 403, code: "FORBIDDEN" });
        }

        const authorizedTargets = (targetUids.length > 0 ? targetUids : memberUids)
          .filter((u) => u !== senderUid && memberUids.includes(u))
          .slice(0, 20);

        const tokens: string[] = [];
        for (const targetUid of authorizedTargets) {
          const tokenSnap = await db.collection("users").doc(targetUid).collection("fcm_tokens").get();
          tokenSnap.docs.forEach((d) => {
            const data = d.data();
            if (data.notificationsEnabled !== false && typeof data.token === "string" && data.token.trim().length > 0) {
              tokens.push(data.token.trim());
            }
          });
        }

        let sentCount = 0;
        if (tokens.length > 0) {
          const fcmRes = await admin.messaging().sendEachForMulticast({
            tokens: tokens.slice(0, 100),
            notification: { title, body: messageBody },
            data: {
              title,
              body: messageBody,
              notificationType,
              deepLinkModule,
              relatedRecordId,
              familyId,
            },
            android: {
              priority: "high",
            },
          });
          sentCount = fcmRes.successCount;
        }

        res.json({ ok: true, sentCount, targetedDevices: tokens.length });
        return;
      }

      case "decisions/configureRules": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const famSnap = await db.collection("families").doc(familyId).get();
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];
        if (fam.ownerId !== callerUid && !adminUids.includes(callerUid)) {
          throw Object.assign(new Error("Only Family Admins can configure approval rules."), { statusCode: 403, code: "ADMIN_REQUIRED" });
        }

        const validated = validateApprovalRulesConfig(body as Partial<ApprovalRulesConfig>);
        const settingsRef = db
          .collection("families")
          .doc(familyId)
          .collection("approval_settings")
          .doc("default");

        await settingsRef.set(
          {
            configId: "default",
            familyId,
            ...validated,
            updatedByUid: callerUid,
            updatedAt: FieldValue.serverTimestamp(),
            createdAt: FieldValue.serverTimestamp(),
          },
          { merge: true }
        );

        await db.collection("auditLogs").doc(`aud_rules_${familyId}_${Date.now()}`).set({
          auditId: `aud_rules_${familyId}_${Date.now()}`,
          familyId,
          actorUserId: callerUid,
          entityCollection: "approval_settings",
          entityId: "default",
          action: "UPDATE",
          summary: `Updated approval rules (${validated.ruleMode}, ${validated.minApprovalPercent}%, quorum ${validated.minQuorumCount})`,
          memberUids: fam.memberUids || [callerUid],
          adminUids: adminUids.length ? adminUids : [callerUid],
          createdAt: FieldValue.serverTimestamp(),
        });

        res.json({ ok: true, familyId, config: validated });
        return;
      }

      case "decisions/createProposal": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const submitNow = body.submitNow !== false;
        const requestedId = String(body.proposalId || "").trim();
        const proposalId =
          requestedId && /^[a-zA-Z0-9_-]{4,80}$/.test(requestedId)
            ? requestedId
            : `prop_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`;

        const title = String(body.title || "").trim().slice(0, 120);
        const category = String(body.category || "").trim() as DecisionCategory;
        const description = String(body.description || "").trim().slice(0, 800);
        const reason = String(body.reason || "").trim().slice(0, 500);
        const amountMinor = Math.trunc(Number(body.amountMinor ?? 0));
        const currency = String(body.currency || "BDT").trim().toUpperCase().slice(0, 6);
        const relatedParty = String(body.relatedParty || "").trim().slice(0, 120);
        const supportingDocName = body.supportingDocName ? String(body.supportingDocName).trim().slice(0, 160) : null;
        const supportingDocUri = body.supportingDocUri ? String(body.supportingDocUri).trim().slice(0, 300) : null;
        const targetSavingsId = body.targetSavingsId ? String(body.targetSavingsId).trim().slice(0, 80) : null;
        const nowMs = Date.now();
        const proposedDateMs = Number(body.proposedDateMs || nowMs);
        const votingDeadlineMs = Number(body.votingDeadlineMs || nowMs + 72 * 3600_000);

        if (title.length < 2) {
          throw Object.assign(new Error("Proposal title is required."), { statusCode: 400, code: "INVALID_TITLE" });
        }
        if (!DECISION_CATEGORIES.includes(category)) {
          throw Object.assign(new Error("Unsupported proposal category."), { statusCode: 400, code: "INVALID_CATEGORY" });
        }
        if (!Number.isFinite(amountMinor) || amountMinor <= 0) {
          throw Object.assign(new Error("Proposal amount must be positive."), { statusCode: 400, code: "INVALID_AMOUNT" });
        }
        if (votingDeadlineMs <= proposedDateMs) {
          throw Object.assign(new Error("Voting deadline must be after the proposed date."), { statusCode: 400, code: "INVALID_DEADLINE" });
        }

        const [famSnap, settingsSnap, membersSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("families").doc(familyId).collection("approval_settings").doc("default").get(),
          db.collection("families").doc(familyId).collection("members").get(),
        ]);
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
        const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];
        const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];
        if (!memberUids.includes(callerUid) || suspendedUids.includes(callerUid)) {
          throw Object.assign(new Error("Active family membership required."), { statusCode: 403, code: "FORBIDDEN" });
        }

        const memberDocs = membersSnap.docs.map((d) => d.data());
        const callerMember = memberDocs.find((m) => m.userId === callerUid);
        const isAdmin = fam.ownerId === callerUid || adminUids.includes(callerUid);
        if (!isAdmin && callerMember && callerMember.canCreateRecords === false) {
          throw Object.assign(new Error("Member lacks permission to create financial proposals."), { statusCode: 403, code: "PERMISSION_DENIED" });
        }

        const rules: ApprovalRulesConfig = settingsSnap.exists
          ? validateApprovalRulesConfig(settingsSnap.data() as Partial<ApprovalRulesConfig>)
          : DEFAULT_APPROVAL_RULES;

        const eligibleVoterUids = resolveEligibleVoters({
          members: memberDocs.map((m) => ({
            userId: String(m.userId || ""),
            role: String(m.role || "MEMBER"),
            canCreateRecords: m.canCreateRecords !== false,
            isSuspended: suspendedUids.includes(String(m.userId || "")),
          })),
          proposerUid: callerUid,
          eligibleVoterRule: rules.eligibleVoterRule,
          specificEligibleUids: rules.specificEligibleUids,
          allowCreatorVote: rules.allowCreatorVote,
        });

        if (submitNow && eligibleVoterUids.length === 0) {
          throw Object.assign(
            new Error("No eligible voters match the active family approval rules."),
            { statusCode: 400, code: "NO_ELIGIBLE_VOTERS" }
          );
        }

        const effectiveQuorum = Math.min(Math.max(1, rules.minQuorumCount), Math.max(1, eligibleVoterUids.length));
        const proposerName = String(callerMember?.displayName || decoded.name || "Family Member");
        const nextStatus: ProposalStatus = submitNow ? "PENDING" : "DRAFT";

        await db.collection("proposals").doc(proposalId).set({
          proposalId,
          familyId,
          title,
          category,
          description,
          reason,
          amountMinor,
          currency: currency || String(fam.currencyCode || "BDT"),
          relatedParty,
          supportingDocName,
          supportingDocUri,
          targetSavingsId,
          proposedDate: Timestamp.fromMillis(proposedDateMs),
          votingDeadline: Timestamp.fromMillis(votingDeadlineMs),
          proposerUid: callerUid,
          proposerName,
          status: nextStatus,
          ruleMode: rules.ruleMode,
          minApprovalPercent: rules.minApprovalPercent,
          minQuorumRequired: effectiveQuorum,
          allowCreatorVote: rules.allowCreatorVote,
          allowVoteChangeBeforeDeadline: rules.allowVoteChangeBeforeDeadline,
          eligibleVoterRule: rules.eligibleVoterRule,
          eligibleVoterUids,
          approvedCount: 0,
          rejectedCount: 0,
          finalDecision: null,
          cancelReason: null,
          cancelledByUid: null,
          executedRecordId: null,
          executedCollection: null,
          executedByUid: null,
          executedAt: null,
          decidedAt: null,
          memberUids,
          adminUids,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        });

        if (submitNow) {
          const batch = db.batch();
          for (const targetUid of eligibleVoterUids.filter((u) => u !== callerUid)) {
            const notifId = `ntf_prop_${proposalId}_${targetUid}`;
            batch.set(db.collection("notifications").doc(notifId), {
              notificationId: notifId,
              familyId,
              recipientUserId: targetUid,
              createdByUserId: callerUid,
              title: `Vote Needed: ${title}`,
              body: `${proposerName} proposed ${currency} ${(amountMinor / 100).toFixed(2)} (${category.replace(/_/g, " ")}).`,
              notificationType: "PROPOSAL_SUBMITTED",
              relatedRecordId: proposalId,
              deepLinkModule: "FAMILY_DECISIONS",
              acknowledged: false,
              recordStatus: "ACTIVE",
              memberUids,
              adminUids,
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          await batch.commit();
        }

        res.json({ ok: true, proposalId, status: nextStatus, eligibleVoterUids });
        return;
      }

      case "decisions/submitVote": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const proposalId = String(body.proposalId || "").trim();
        const choice = String(body.choice || "").trim().toUpperCase() as VoteChoice;
        const comment = body.comment ? String(body.comment).trim().slice(0, 240) : null;

        if (choice !== "APPROVE" && choice !== "REJECT") {
          throw Object.assign(new Error("Vote choice must be APPROVE or REJECT."), { statusCode: 400, code: "INVALID_VOTE_CHOICE" });
        }

        const famSnap = await db.collection("families").doc(familyId).get();
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];
        if (suspendedUids.includes(callerUid)) {
          throw Object.assign(new Error("Suspended members cannot vote."), { statusCode: 403, code: "SUSPENDED" });
        }

        const propRef = db.collection("proposals").doc(proposalId);
        const votesRef = propRef.collection("votes");

        const result = await db.runTransaction(async (tx) => {
          const [propSnap, allVotesSnap, memberSnap] = await Promise.all([
            tx.get(propRef),
            tx.get(votesRef),
            tx.get(db.collection("families").doc(familyId).collection("members").doc(callerUid)),
          ]);
          if (!propSnap.exists) {
            throw Object.assign(new Error("Proposal not found."), { statusCode: 404, code: "PROPOSAL_NOT_FOUND" });
          }
          const prop = propSnap.data()!;
          if (prop.familyId !== familyId) {
            throw Object.assign(new Error("Cross-family vote denied."), { statusCode: 403, code: "FORBIDDEN" });
          }

          const eligibleVoterUids: string[] = Array.isArray(prop.eligibleVoterUids) ? prop.eligibleVoterUids : [];
          if (!eligibleVoterUids.includes(callerUid)) {
            throw Object.assign(new Error("You are not in the eligible voters list for this proposal."), { statusCode: 403, code: "NOT_ELIGIBLE_VOTER" });
          }
          if (prop.allowCreatorVote === false && prop.proposerUid === callerUid) {
            throw Object.assign(new Error("Proposal creator is restricted from voting by family rules."), { statusCode: 403, code: "CREATOR_VOTE_DISABLED" });
          }

          const deadlineMs = prop.votingDeadline?.toMillis ? prop.votingDeadline.toMillis() : Number(prop.votingDeadline || 0);
          const nowMs = Date.now();
          if (prop.status !== "PENDING") {
            throw Object.assign(new Error(`Voting is closed (status: ${prop.status}).`), { statusCode: 400, code: "VOTING_CLOSED" });
          }
          if (nowMs > deadlineMs) {
            tx.update(propRef, {
              status: "EXPIRED",
              finalDecision: "EXPIRED",
              decidedAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
            throw Object.assign(new Error("Voting deadline has expired."), { statusCode: 400, code: "DEADLINE_EXPIRED" });
          }

          const existingVoteDoc = allVotesSnap.docs.find((d) => d.id === callerUid);
          const previousChoice = existingVoteDoc ? String(existingVoteDoc.data().choice || "") : null;
          if (existingVoteDoc && prop.allowVoteChangeBeforeDeadline !== true) {
            throw Object.assign(new Error("Vote changes are disabled by family approval settings."), { statusCode: 403, code: "VOTE_CHANGE_DISABLED" });
          }
          if (previousChoice === choice) {
            throw Object.assign(new Error("Duplicate identical vote ignored."), { statusCode: 409, code: "DUPLICATE_VOTE" });
          }

          const voterName = String(memberSnap.data()?.displayName || decoded.name || "Family Member");
          const updatedVotes = allVotesSnap.docs
            .filter((d) => d.id !== callerUid)
            .map((d) => ({ voterUid: d.id, choice: String(d.data().choice) as VoteChoice }));
          updatedVotes.push({ voterUid: callerUid, choice });

          const tally = evaluateProposalTally({
            eligibleVoterUids,
            votes: updatedVotes,
            ruleMode: prop.ruleMode,
            minApprovalPercent: Number(prop.minApprovalPercent || 60),
            minQuorumRequired: Number(prop.minQuorumRequired || 1),
            votingDeadlineMs: deadlineMs,
            nowMs,
            currentStatus: "PENDING",
          });

          const voteRef = votesRef.doc(callerUid);
          tx.set(
            voteRef,
            {
              voterUid: callerUid,
              voterName,
              proposalId,
              familyId,
              choice,
              comment,
              memberUids: prop.memberUids || fam.memberUids || [callerUid],
              votedAt: existingVoteDoc?.data()?.votedAt || FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

          const histId = `vh_${nowMs}_${callerUid.slice(0, 8)}`;
          tx.set(propRef.collection("vote_history").doc(histId), {
            historyId: histId,
            proposalId,
            familyId,
            actorUid: callerUid,
            actorName: voterName,
            actionType: existingVoteDoc ? "VOTE_CHANGED" : "VOTE_CAST",
            previousChoice,
            newChoice: choice,
            note: comment,
            memberUids: prop.memberUids || fam.memberUids || [callerUid],
            createdAt: FieldValue.serverTimestamp(),
          });

          const propUpdates: Record<string, unknown> = {
            approvedCount: tally.approvedCount,
            rejectedCount: tally.rejectedCount,
            status: tally.nextStatus,
            finalDecision: tally.finalDecision,
            updatedAt: FieldValue.serverTimestamp(),
          };
          if (tally.nextStatus === "APPROVED" || tally.nextStatus === "REJECTED") {
            propUpdates.decidedAt = FieldValue.serverTimestamp();
          }
          tx.update(propRef, propUpdates);

          return {
            tally,
            title: String(prop.title || ""),
            proposerUid: String(prop.proposerUid || ""),
            memberUids: (prop.memberUids || fam.memberUids || []) as string[],
            adminUids: (prop.adminUids || fam.adminUids || []) as string[],
          };
        });

        if (result.tally.nextStatus === "APPROVED" || result.tally.nextStatus === "REJECTED") {
          const nType = result.tally.nextStatus === "APPROVED" ? "PROPOSAL_APPROVED" : "PROPOSAL_REJECTED";
          const batch = db.batch();
          for (const uid of result.memberUids) {
            const nid = `ntf_${result.tally.nextStatus.toLowerCase()}_${proposalId}_${uid}`;
            batch.set(db.collection("notifications").doc(nid), {
              notificationId: nid,
              familyId,
              recipientUserId: uid,
              createdByUserId: callerUid,
              title: `Proposal ${result.tally.nextStatus === "APPROVED" ? "Approved" : "Rejected"}: ${result.title}`,
              body: `${result.tally.approvedCount} approve, ${result.tally.rejectedCount} reject.`,
              notificationType: nType,
              relatedRecordId: proposalId,
              deepLinkModule: "FAMILY_DECISIONS",
              acknowledged: false,
              recordStatus: "ACTIVE",
              memberUids: result.memberUids,
              adminUids: result.adminUids,
              createdAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          await batch.commit();
        }

        res.json({ ok: true, proposalId, tally: result.tally });
        return;
      }

      case "decisions/cancelProposal": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const proposalId = String(body.proposalId || "").trim();
        const cancelReason = assertValidCancelReason(body.cancelReason);

        const [famSnap, propSnap] = await Promise.all([
          db.collection("families").doc(familyId).get(),
          db.collection("proposals").doc(proposalId).get(),
        ]);
        if (!famSnap.exists || !propSnap.exists) {
          throw Object.assign(new Error("Proposal or family not found."), { statusCode: 404, code: "NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const prop = propSnap.data()!;
        if (prop.familyId !== familyId) {
          throw Object.assign(new Error("Cross-family action denied."), { statusCode: 403, code: "FORBIDDEN" });
        }
        const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];
        const isAdmin = fam.ownerId === callerUid || adminUids.includes(callerUid);
        const isProposerDraft = prop.proposerUid === callerUid && prop.status === "DRAFT";
        if (!isAdmin && !isProposerDraft) {
          throw Object.assign(new Error("Only Family Admins can cancel active proposals."), { statusCode: 403, code: "ADMIN_REQUIRED" });
        }
        if (prop.status !== "DRAFT" && prop.status !== "PENDING") {
          throw Object.assign(new Error(`Cannot cancel proposal in status ${prop.status}.`), { statusCode: 400, code: "INVALID_STATE" });
        }

        await db.runTransaction(async (tx) => {
          tx.update(db.collection("proposals").doc(proposalId), {
            status: "CANCELLED",
            finalDecision: "CANCELLED",
            cancelReason,
            cancelledByUid: callerUid,
            decidedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          const histId = `vh_cancel_${Date.now()}`;
          tx.set(db.collection("proposals").doc(proposalId).collection("vote_history").doc(histId), {
            historyId: histId,
            proposalId,
            familyId,
            actorUid: callerUid,
            actorName: decoded.name || "Family Admin",
            actionType: "PROPOSAL_CANCELLED",
            previousChoice: null,
            newChoice: null,
            note: cancelReason,
            memberUids: prop.memberUids || fam.memberUids || [callerUid],
            createdAt: FieldValue.serverTimestamp(),
          });
        });

        res.json({ ok: true, proposalId, status: "CANCELLED" });
        return;
      }

      case "decisions/executeProposal": {
        const decoded = await verifyBearerUser(req);
        const callerUid = decoded.uid;
        const familyId = String(body.familyId || "").trim();
        const proposalId = String(body.proposalId || "").trim();

        const famSnap = await db.collection("families").doc(familyId).get();
        if (!famSnap.exists) {
          throw Object.assign(new Error("Family workspace not found."), { statusCode: 404, code: "FAMILY_NOT_FOUND" });
        }
        const fam = famSnap.data()!;
        const memberUids: string[] = Array.isArray(fam.memberUids) ? fam.memberUids : [];
        const adminUids: string[] = Array.isArray(fam.adminUids) ? fam.adminUids : [];
        const suspendedUids: string[] = Array.isArray(fam.suspendedUids) ? fam.suspendedUids : [];
        if (!memberUids.includes(callerUid) || suspendedUids.includes(callerUid)) {
          throw Object.assign(new Error("Active family membership required."), { statusCode: 403, code: "FORBIDDEN" });
        }

        const propRef = db.collection("proposals").doc(proposalId);
        const execOutcome = await db.runTransaction(async (tx) => {
          const propSnap = await tx.get(propRef);
          if (!propSnap.exists) {
            throw Object.assign(new Error("Proposal not found."), { statusCode: 404, code: "PROPOSAL_NOT_FOUND" });
          }
          const prop = propSnap.data()!;
          if (prop.familyId !== familyId) {
            throw Object.assign(new Error("Cross-family execution denied."), { statusCode: 403, code: "FORBIDDEN" });
          }
          if (prop.executedRecordId || prop.status === "EXECUTED") {
            throw Object.assign(new Error("Proposal has already been executed."), { statusCode: 409, code: "ALREADY_EXECUTED" });
          }
          if (prop.status !== "APPROVED") {
            throw Object.assign(new Error(`Only APPROVED proposals can execute (current: ${prop.status}).`), {
              statusCode: 400,
              code: "NOT_APPROVED",
            });
          }

          const category = prop.category as DecisionCategory;
          const desc = buildExecutionDescriptor(proposalId, category);
          const amountMinor = Number(prop.amountMinor || 0);
          const amountMajor = amountMinor / 100.0;
          const recordId = `rec_${desc.idempotencyKey}`;
          const nowTs = FieldValue.serverTimestamp();

          if (desc.targetCollection === "loans") {
            tx.set(db.collection("loans").doc(recordId), {
              recordId,
              familyId,
              createdByUserId: callerUid,
              amount: amountMajor,
              amountMinor,
              remainingBalance: amountMajor,
              remainingBalanceMinor: amountMinor,
              currency: prop.currency || "BDT",
              category: category === "TAKE_LOAN" ? "Approved Loan In" : "Approved Loan Out",
              description: `${prop.title}: ${prop.reason}`.slice(0, 300),
              recordStatus: "ACTIVE",
              loanDirection: desc.loanDirection,
              counterparty: String(prop.relatedParty || "Counterparty").slice(0, 80),
              idempotencyKey: desc.idempotencyKey,
              syncVersion: 1,
              memberUids,
              adminUids,
              createdAt: nowTs,
              updatedAt: nowTs,
            });
          } else if (desc.targetCollection === "investments") {
            tx.set(db.collection("investments").doc(recordId), {
              recordId,
              familyId,
              createdByUserId: callerUid,
              amount: amountMajor,
              amountMinor,
              currentValue: amountMajor,
              currentValueMinor: amountMinor,
              currency: prop.currency || "BDT",
              category: "Approved Investment",
              description: `${prop.title}: ${prop.reason}`.slice(0, 300),
              assetName: String(prop.relatedParty || prop.title).slice(0, 80),
              assetType: "OTHER",
              recordStatus: "ACTIVE",
              idempotencyKey: desc.idempotencyKey,
              syncVersion: 1,
              memberUids,
              adminUids,
              createdAt: nowTs,
              updatedAt: nowTs,
            });
          } else if (desc.targetCollection === "savings") {
            const targetSavingsId = String(prop.targetSavingsId || "").trim();
            if (!targetSavingsId) {
              throw Object.assign(new Error("Target savings goal ID is required to withdraw savings."), {
                statusCode: 400,
                code: "SAVINGS_TARGET_REQUIRED",
              });
            }
            const savRef = db.collection("savings").doc(targetSavingsId);
            const savSnap = await tx.get(savRef);
            if (!savSnap.exists || savSnap.data()?.familyId !== familyId) {
              throw Object.assign(new Error("Target savings goal not found."), { statusCode: 404, code: "SAVINGS_NOT_FOUND" });
            }
            const sav = savSnap.data()!;
            const currentMinor = Number(sav.amountMinor ?? Math.round(Number(sav.amount || 0) * 100));
            if (currentMinor < amountMinor) {
              throw Object.assign(new Error("Insufficient savings balance for approved withdrawal."), {
                statusCode: 400,
                code: "INSUFFICIENT_SAVINGS",
              });
            }
            const nextMinor = currentMinor - amountMinor;
            tx.update(savRef, {
              amountMinor: nextMinor,
              amount: nextMinor / 100.0,
              syncVersion: Number(sav.syncVersion || 1) + 1,
              updatedAt: nowTs,
            });
          } else {
            tx.set(db.collection("transactions").doc(recordId), {
              recordId,
              transactionId: recordId,
              familyId,
              createdByUserId: callerUid,
              memberId: callerUid,
              amount: amountMajor,
              amountMinor,
              currency: prop.currency || "BDT",
              category: category === "LARGE_PURCHASE" ? "Major Purchase" : "Approved Expense",
              description: `${prop.title}: ${prop.reason}`.slice(0, 300),
              recordStatus: "ACTIVE",
              transactionType: "EXPENSE",
              occurredAt: nowTs,
              idempotencyKey: desc.idempotencyKey,
              syncVersion: 1,
              memberUids,
              adminUids,
              createdAt: nowTs,
              updatedAt: nowTs,
            });
          }

          const executedTargetId = desc.targetCollection === "savings" ? String(prop.targetSavingsId) : recordId;
          tx.update(propRef, {
            status: "EXECUTED",
            finalDecision: "EXECUTED",
            executedRecordId: executedTargetId,
            executedCollection: desc.targetCollection,
            executedByUid: callerUid,
            executedAt: nowTs,
            updatedAt: nowTs,
          });

          const audId = `aud_exec_${proposalId}`;
          tx.set(db.collection("auditLogs").doc(audId), {
            auditId: audId,
            familyId,
            actorUserId: callerUid,
            entityCollection: desc.targetCollection,
            entityId: executedTargetId,
            action: "CREATE",
            summary: `Executed approved proposal ${prop.title} (${prop.currency} ${amountMajor.toFixed(2)})`,
            memberUids,
            adminUids,
            createdAt: nowTs,
          });

          return { executedRecordId: executedTargetId, executedCollection: desc.targetCollection };
        });

        res.json({ ok: true, proposalId, status: "EXECUTED", ...execOutcome });
        return;
      }

      default:
        res.status(404).json({ ok: false, code: "NOT_FOUND", message: `Unknown auth route: ${route}` });
    }
  } catch (err) {
    sendError(res, err);
  }
});
