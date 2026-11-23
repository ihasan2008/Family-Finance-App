import * as crypto from "node:crypto";

const SCRYPT_N = 16384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const KEY_LEN = 64;

export function normalizeE164Phone(raw: string): string {
  const trimmed = raw.trim().replace(/[\s\-().]/g, "");
  if (!/^\+[1-9][0-9]{6,14}$/.test(trimmed)) {
    throw new Error("Invalid mobile number format. Use international E.164 format (e.g. +14155550123).");
  }
  return trimmed;
}

export function hashPhoneIndexKey(e164Phone: string): string {
  return crypto.createHash("sha256").update(e164Phone, "utf8").digest("hex");
}

export function validatePasswordStrength(password: string): void {
  if (password.length < 8 || password.length > 128) {
    throw new Error("Password must be between 8 and 128 characters.");
  }
  const hasUpper = /[A-Z]/.test(password);
  const hasLower = /[a-z]/.test(password);
  const hasDigit = /[0-9]/.test(password);
  const hasSpecial = /[^A-Za-z0-9]/.test(password);
  if (!hasUpper || !hasLower || !hasDigit || !hasSpecial) {
    throw new Error("Password must include uppercase, lowercase, numeric, and special characters.");
  }
}

export function hashPasswordScrypt(password: string): { saltHex: string; hashHex: string } {
  validatePasswordStrength(password);
  const salt = crypto.randomBytes(16);
  const derived = crypto.scryptSync(password, salt, KEY_LEN, {
    N: SCRYPT_N,
    r: SCRYPT_R,
    p: SCRYPT_P,
    maxmem: 64 * 1024 * 1024,
  });
  return {
    saltHex: salt.toString("hex"),
    hashHex: derived.toString("hex"),
  };
}

export function verifyPasswordScrypt(password: string, saltHex: string, storedHashHex: string): boolean {
  if (!password || !saltHex || !storedHashHex) return false;
  const salt = Buffer.from(saltHex, "hex");
  const expected = Buffer.from(storedHashHex, "hex");
  const actual = crypto.scryptSync(password, salt, expected.length, {
    N: SCRYPT_N,
    r: SCRYPT_R,
    p: SCRYPT_P,
    maxmem: 64 * 1024 * 1024,
  });
  if (actual.length !== expected.length) return false;
  return crypto.timingSafeEqual(actual, expected);
}

export function computeAndroidApkKeyHashOrigin(sha256Fingerprint: string): string {
  const cleanHex = sha256Fingerprint.replace(/:/g, "").trim();
  const rawBytes = Buffer.from(cleanHex, "hex");
  const base64Url = rawBytes
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `android:apk-key-hash:${base64Url}`;
}

export interface RateLimitDecision {
  allowed: boolean;
  retryAfterSeconds: number;
  nextAttemptCount: number;
  lockedUntilMs: number | null;
}

export function evaluateSlidingRateLimit(
  nowMs: number,
  windowStartMs: number,
  attemptCount: number,
  lockedUntilMs: number | null,
  maxAttempts: number,
  windowMs: number,
  lockoutDurationMs: number
): RateLimitDecision {
  if (lockedUntilMs !== null && nowMs < lockedUntilMs) {
    return {
      allowed: false,
      retryAfterSeconds: Math.ceil((lockedUntilMs - nowMs) / 1000),
      nextAttemptCount: attemptCount,
      lockedUntilMs,
    };
  }
  const withinWindow = nowMs - windowStartMs < windowMs;
  const currentCount = withinWindow ? attemptCount : 0;
  const nextCount = currentCount + 1;
  if (nextCount > maxAttempts) {
    const newLockUntil = nowMs + lockoutDurationMs;
    return {
      allowed: false,
      retryAfterSeconds: Math.ceil(lockoutDurationMs / 1000),
      nextAttemptCount: nextCount,
      lockedUntilMs: newLockUntil,
    };
  }
  return {
    allowed: true,
    retryAfterSeconds: 0,
    nextAttemptCount: nextCount,
    lockedUntilMs: null,
  };
}

const FAMILY_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const FAMILY_ID_LENGTH = 12;

export function generateRandomFamilyId(): string {
  let out = "";
  for (let i = 0; i < FAMILY_ID_LENGTH; i++) {
    out += FAMILY_ID_ALPHABET[crypto.randomInt(0, FAMILY_ID_ALPHABET.length)];
  }
  return out;
}

export function isValidFamilyId12(familyId: string): boolean {
  return /^[A-Z0-9]{12}$/.test(familyId.trim().toUpperCase());
}

export function normalizeFamilyIdInput(rawFamilyId: string): string {
  return rawFamilyId.trim().toUpperCase();
}

const MEMBER_CARD_ID_LENGTH = 16;

export function generateMemberCardId16(): string {
  let out = "";
  for (let i = 0; i < MEMBER_CARD_ID_LENGTH; i++) {
    out += FAMILY_ID_ALPHABET[crypto.randomInt(0, FAMILY_ID_ALPHABET.length)];
  }
  return out;
}

export function isValidMemberCardId16(rawId: string): boolean {
  return /^[A-Z0-9]{16}$/.test(rawId.trim().toUpperCase());
}

export function validateDateOfBirthIso(rawDob: string): string {
  const dob = rawDob.trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dob);
  if (!match) {
    throw new Error("Date of birth must be in YYYY-MM-DD format.");
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const currentYear = new Date().getUTCFullYear();
  if (year < 1900 || year > currentYear) {
    throw new Error("Date of birth year is out of valid range.");
  }
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day
  ) {
    throw new Error("Date of birth is not a valid calendar date.");
  }
  if (parsed.getTime() > Date.now()) {
    throw new Error("Date of birth cannot be in the future.");
  }
  return dob;
}

export function maskPhoneNumber(e164Phone: string): string {
  const normalized = e164Phone.trim();
  if (normalized.length <= 6) {
    return normalized;
  }
  const prefixLen = Math.min(6, normalized.length - 4);
  const tail = normalized.slice(-4);
  const maskedCount = Math.max(4, normalized.length - prefixLen - 4);
  return `${normalized.slice(0, prefixLen)}${"•".repeat(maskedCount)}${tail}`;
}

export function resolveMemberIdFromPhone(rawPhone: string): string {
  return normalizeE164Phone(rawPhone);
}

export function normalizeInvitationToken(rawToken: string): string {
  return rawToken.trim().toUpperCase();
}

export function hashInvitationToken(rawToken: string): string {
  const normalized = normalizeInvitationToken(rawToken);
  if (normalized.length < 12 || normalized.length > 64) {
    throw new Error("Invalid invitation token format.");
  }
  return crypto.createHash("sha256").update(normalized, "utf8").digest("hex");
}

export function generateInvitationToken(): {
  token: string;
  tokenHash: string;
  tokenPreview: string;
} {
  const raw = crypto.randomBytes(12).toString("hex").toUpperCase();
  const token = `FFINV-${raw.slice(0, 6)}-${raw.slice(6, 12)}-${raw.slice(12, 18)}`;
  const tokenHash = hashInvitationToken(token);
  const tokenPreview = `FFINV-${raw.slice(0, 4)}••••${raw.slice(14, 18)}`;
  return { token, tokenHash, tokenPreview };
}

