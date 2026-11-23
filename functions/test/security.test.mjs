import { test } from "node:test";
import assert from "node:assert/strict";
import * as crypto from "node:crypto";

function normalizeE164Phone(raw) {
  const trimmed = raw.trim().replace(/[\s\-().]/g, "");
  if (!/^\+[1-9][0-9]{6,14}$/.test(trimmed)) {
    throw new Error("Invalid mobile number format.");
  }
  return trimmed;
}

function hashPasswordScrypt(password) {
  const salt = crypto.randomBytes(16);
  const derived = crypto.scryptSync(password, salt, 64, { N: 16384, r: 8, p: 1 });
  return { saltHex: salt.toString("hex"), hashHex: derived.toString("hex") };
}

function verifyPasswordScrypt(password, saltHex, storedHashHex) {
  const actual = crypto.scryptSync(password, Buffer.from(saltHex, "hex"), 64, { N: 16384, r: 8, p: 1 });
  const expected = Buffer.from(storedHashHex, "hex");
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function generateRandomFamilyId12() {
  const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  let out = "";
  for (let i = 0; i < 12; i++) {
    out += alphabet[crypto.randomInt(0, alphabet.length)];
  }
  return out;
}

function validateDateOfBirthIso(rawDob) {
  const dob = rawDob.trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dob);
  if (!match) throw new Error("Invalid DOB format");
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (
    year < 1900 ||
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day ||
    parsed.getTime() > Date.now()
  ) {
    throw new Error("Invalid DOB calendar date");
  }
  return dob;
}

function maskPhoneNumber(e164Phone) {
  const normalized = e164Phone.trim();
  if (normalized.length <= 6) return normalized;
  const prefixLen = Math.min(6, normalized.length - 4);
  const tail = normalized.slice(-4);
  const maskedCount = Math.max(4, normalized.length - prefixLen - 4);
  return `${normalized.slice(0, prefixLen)}${"•".repeat(maskedCount)}${tail}`;
}

test("normalizeE164Phone normalizes valid phone numbers and rejects invalid formats", () => {
  assert.equal(normalizeE164Phone("+1 (415) 555-0199"), "+14155550199");
  assert.equal(normalizeE164Phone("+880 1711-223344"), "+8801711223344");
  assert.throws(() => normalizeE164Phone("017123456"));
});

test("scrypt password hashing verifies matching password and rejects wrong password", () => {
  const { saltHex, hashHex } = hashPasswordScrypt("F@milyFinance99");
  assert.equal(verifyPasswordScrypt("F@milyFinance99", saltHex, hashHex), true);
  assert.equal(verifyPasswordScrypt("WrongPassword!", saltHex, hashHex), false);
});

test("Family ID is exact 12-char [A-Z0-9]{12} and invitation tokens hash consistently", () => {
  const idA = generateRandomFamilyId12();
  const idB = generateRandomFamilyId12();
  assert.match(idA, /^[A-Z0-9]{12}$/);
  assert.match(idB, /^[A-Z0-9]{12}$/);
  assert.notEqual(idA, idB);

  const rawToken = `FFINV-${crypto.randomBytes(12).toString("hex").toUpperCase()}`;
  const hashA = crypto.createHash("sha256").update(rawToken.trim().toUpperCase(), "utf8").digest("hex");
  const hashB = crypto.createHash("sha256").update(`  ${rawToken.toLowerCase()}  `.trim().toUpperCase(), "utf8").digest("hex");
  assert.match(hashA, /^[a-f0-9]{64}$/);
  assert.equal(hashA, hashB);
});

test("Date of birth validation and phone masking protect member identity privacy", () => {
  assert.equal(validateDateOfBirthIso("1996-02-29"), "1996-02-29");
  assert.throws(() => validateDateOfBirthIso("1997-02-29"));
  assert.throws(() => validateDateOfBirthIso("2099-01-01"));
  assert.throws(() => validateDateOfBirthIso("01/05/1995"));

  const masked = maskPhoneNumber("+8801711223344");
  assert.ok(masked.startsWith("+88017"));
  assert.ok(masked.endsWith("3344"));
  assert.ok(masked.includes("••••"));
});

test("Member Card ID contains exactly 16 random uppercase alphanumeric characters", () => {
  const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  const gen16 = () => {
    let out = "";
    for (let i = 0; i < 16; i++) out += alphabet[crypto.randomInt(0, alphabet.length)];
    return out;
  };
  const cardA = gen16();
  const cardB = gen16();
  assert.match(cardA, /^[A-Z0-9]{16}$/);
  assert.match(cardB, /^[A-Z0-9]{16}$/);
  assert.notEqual(cardA, cardB);
});

