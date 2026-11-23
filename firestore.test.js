const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const crypto = require("node:crypto");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_101";
const BOB_UID = "bob_uid_202";
const CAROL_UID = "carol_uid_303";
const FAMILY_ID = "fam_a1b2c3d4e5f607080910";
const OTHER_FAMILY_ID = "fam_other_002";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function sha256Hex(value) {
  return crypto.createHash("sha256").update(value.trim().toUpperCase(), "utf8").digest("hex");
}

function makeFamilyPayload(ownerUid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    name: "Rahman Household",
    ownerId: ownerUid,
    createdByUserId: ownerUid,
    currencyCode: "USD",
    memberUids: [ownerUid],
    adminUids: [ownerUid],
    description: "Primary household budget & savings",
    requireAdminApproval: true,
    memberChatEnabled: true,
    membersCanAddExpenses: true,
    budgetStartDay: 1,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function makeAdminMemberPayload(familyId, uid, displayName = "Alice Rahman", overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    familyId,
    displayName,
    role: "ADMIN",
    memberUids: [uid],
    adminUids: [uid],
    relationLabel: "Family Admin",
    canCreateRecords: true,
    canViewSharedFinances: true,
    canParticipateInChat: true,
    joinedAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied access to users and families", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("families").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(
    unauthDb.collection("families").doc(FAMILY_ID).set(makeFamilyPayload(ALICE_UID))
  );
});

test("UserProfile: owner create, partial update, and strict PII isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Rahman",
      email: "alice@example.com",
      themePreference: "LIGHT",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themePreference: "DARK",
      activeFamilyId: FAMILY_ID,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Family Creation & Isolation: Admin creator batch, recovery queries, and cross-family denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("families").doc(FAMILY_ID), makeFamilyPayload(ALICE_UID, { createdAt: now, updatedAt: now }));
  batch.set(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(ALICE_UID),
    makeAdminMemberPayload(FAMILY_ID, ALICE_UID, "Alice Rahman", { joinedAt: now, updatedAt: now })
  );
  await assertSucceeds(batch.commit());

  // Filtered member & Admin Family ID recovery queries succeed for Alice
  await assertSucceeds(
    aliceDb.collection("families").where("memberUids", "array-contains", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("families").where("ownerId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("families").where("createdByUserId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members")
      .where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Unfiltered list queries fail
  await assertFails(aliceDb.collection("families").get());
  await assertFails(aliceDb.collection("family_invites").get());

  // Cross-family isolation: Bob cannot read or query Alice's family
  await assertFails(bobDb.collection("families").doc(FAMILY_ID).get());
  await assertFails(bobDb.collection("families").where("ownerId", "==", ALICE_UID).get());
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("members")
      .where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Reject creating family with extra @DocumentId `id` field or forged future timestamps
  await assertFails(
    aliceDb.collection("families").doc(OTHER_FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, { id: OTHER_FAMILY_ID })
    )
  );
  await assertFails(
    aliceDb.collection("families").doc(OTHER_FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        createdAt: new Date(Date.now() + 60000),
        updatedAt: new Date(Date.now() + 60000),
      })
    )
  );
});

test("Joining System: block Family ID-only joins, enforce expiring tokens & Admin approval", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // Alice creates family
  const createBatch = aliceDb.batch();
  createBatch.set(aliceDb.collection("families").doc(FAMILY_ID), makeFamilyPayload(ALICE_UID, { createdAt: now, updatedAt: now }));
  createBatch.set(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(ALICE_UID),
    makeAdminMemberPayload(FAMILY_ID, ALICE_UID, "Alice Rahman", { joinedAt: now, updatedAt: now })
  );
  await assertSucceeds(createBatch.commit());

  // SECURITY: Bob knowing only FAMILY_ID cannot self-join members, cannot mutate memberUids, and cannot forge join_requests
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID).set({
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob",
      role: "MEMBER",
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      canCreateRecords: true,
      canViewSharedFinances: true,
      canParticipateInChat: true,
      joinedAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("join_requests").doc(BOB_UID).set({
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob",
      invitationId: "inv_fake",
      tokenHash: sha256Hex("FFINV-FAKE-TOKEN-1234"),
      requestedRole: "MEMBER",
      status: "PENDING_APPROVAL",
      requestedAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot create an invitation for Alice's family
  const rawToken = "FFINV-9988AA-BBCCDD-112233";
  const tokenHash = sha256Hex(rawToken);
  const futureExpiry = new Date(Date.now() + 24 * 60 * 60 * 1000);
  await assertFails(
    bobDb.collection("family_invites").doc(tokenHash).set({
      tokenHash,
      invitationId: "inv_01",
      familyId: FAMILY_ID,
      familyName: "Rahman Household",
      createdByUid: BOB_UID,
      targetRole: "MEMBER",
      status: "ACTIVE",
      maxUses: 1,
      usedCount: 0,
      expiresAt: futureExpiry,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice (Admin) creates a valid expiring invitation
  const invBatch = aliceDb.batch();
  invBatch.set(aliceDb.collection("family_invites").doc(tokenHash), {
    tokenHash,
    invitationId: "inv_01",
    familyId: FAMILY_ID,
    familyName: "Rahman Household",
    createdByUid: ALICE_UID,
    targetRole: "MEMBER",
    inviteeHint: "Partner",
    status: "ACTIVE",
    maxUses: 1,
    usedCount: 0,
    expiresAt: futureExpiry,
    createdAt: now,
    updatedAt: now,
  });
  invBatch.set(
    aliceDb.collection("families").doc(FAMILY_ID).collection("invitations").doc("inv_01"),
    {
      invitationId: "inv_01",
      familyId: FAMILY_ID,
      tokenHash,
      token: rawToken,
      tokenPreview: "FFINV-9988••••2233",
      targetRole: "MEMBER",
      inviteeHint: "Partner",
      status: "ACTIVE",
      maxUses: 1,
      usedCount: 0,
      createdByUid: ALICE_UID,
      expiresAt: futureExpiry,
      createdAt: now,
      updatedAt: now,
    }
  );
  await assertSucceeds(invBatch.commit());

  // Non-admin Bob cannot list family invitations
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("invitations").get()
  );

  // Expired invitation is rejected by rules
  const expiredToken = "FFINV-EXPIRED-0000-1111";
  const expiredHash = sha256Hex(expiredToken);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("family_invites").doc(expiredHash).set({
      tokenHash: expiredHash,
      invitationId: "inv_expired",
      familyId: FAMILY_ID,
      familyName: "Rahman Household",
      createdByUid: ALICE_UID,
      targetRole: "MEMBER",
      status: "ACTIVE",
      maxUses: 1,
      usedCount: 0,
      expiresAt: new Date(Date.now() - 60000),
      createdAt: now,
      updatedAt: now,
    });
  });
  await assertFails(
    carolDb.collection("families").doc(FAMILY_ID).collection("join_requests").doc(CAROL_UID).set({
      userId: CAROL_UID,
      familyId: FAMILY_ID,
      displayName: "Carol",
      invitationId: "inv_expired",
      tokenHash: expiredHash,
      requestedRole: "MEMBER",
      status: "PENDING_APPROVAL",
      requestedAt: now,
      updatedAt: now,
    })
  );

  // Bob submits a valid join request using the active invitation token
  await assertSucceeds(
    bobDb.collection("families").doc(FAMILY_ID).collection("join_requests").doc(BOB_UID).set({
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob Rahman",
      relationLabel: "Partner",
      invitationId: "inv_01",
      tokenHash,
      requestedRole: "MEMBER",
      status: "PENDING_APPROVAL",
      requestedAt: now,
      updatedAt: now,
    })
  );

  // Before Admin approval, Bob STILL cannot read family data and cannot self-approve!
  await assertFails(bobDb.collection("families").doc(FAMILY_ID).get());
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("join_requests").doc(BOB_UID).update({
      status: "APPROVED",
      reviewedByUid: BOB_UID,
      reviewedAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice (Admin) approves Bob's join request atomically
  const approveBatch = aliceDb.batch();
  approveBatch.update(aliceDb.collection("families").doc(FAMILY_ID), {
    memberUids: [ALICE_UID, BOB_UID],
    updatedAt: new Date(Date.now() - 400),
  });
  approveBatch.update(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(ALICE_UID),
    {
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: new Date(Date.now() - 400),
    }
  );
  approveBatch.set(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID),
    {
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob Rahman",
      role: "MEMBER",
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      relationLabel: "Partner",
      canCreateRecords: true,
      canViewSharedFinances: true,
      canParticipateInChat: true,
      joinedAt: new Date(Date.now() - 400),
      updatedAt: new Date(Date.now() - 400),
    }
  );
  approveBatch.update(
    aliceDb.collection("families").doc(FAMILY_ID).collection("join_requests").doc(BOB_UID),
    {
      status: "APPROVED",
      reviewedByUid: ALICE_UID,
      reviewedAt: new Date(Date.now() - 400),
      updatedAt: new Date(Date.now() - 400),
    }
  );
  approveBatch.update(aliceDb.collection("family_invites").doc(tokenHash), {
    usedCount: 1,
    status: "USED",
    updatedAt: new Date(Date.now() - 400),
  });
  approveBatch.update(
    aliceDb.collection("families").doc(FAMILY_ID).collection("invitations").doc("inv_01"),
    {
      usedCount: 1,
      status: "USED",
      updatedAt: new Date(Date.now() - 400),
    }
  );
  await assertSucceeds(approveBatch.commit());

  // Now Bob IS an authorized member and can read the family workspace
  await assertSucceeds(bobDb.collection("families").doc(FAMILY_ID).get());

  // Regular Member Bob cannot change roles, settings, or transfer ownership
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).update({
      name: "Hijacked Household",
      updatedAt: new Date(Date.now() - 200),
    })
  );
  await assertFails(
    bobDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID).update({
      role: "ADMIN",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Alice transfers primary ownership to Bob while createdByUserId remains immutable
  const transferBatch = aliceDb.batch();
  transferBatch.update(aliceDb.collection("families").doc(FAMILY_ID), {
    ownerId: BOB_UID,
    adminUids: [BOB_UID],
    updatedAt: new Date(Date.now() - 100),
  });
  transferBatch.update(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID),
    {
      role: "ADMIN",
      adminUids: [BOB_UID],
      updatedAt: new Date(Date.now() - 100),
    }
  );
  transferBatch.update(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(ALICE_UID),
    {
      role: "MEMBER",
      adminUids: [BOB_UID],
      updatedAt: new Date(Date.now() - 100),
    }
  );
  await assertSucceeds(transferBatch.commit());

  // Original creator Alice can still recover the Family ID via createdByUserId link
  await assertSucceeds(
    aliceDb.collection("families").where("createdByUserId", "==", ALICE_UID).get()
  );

  // New primary Admin Bob can remove Member Alice
  const removeBatch = bobDb.batch();
  removeBatch.update(bobDb.collection("families").doc(FAMILY_ID), {
    memberUids: [BOB_UID],
    adminUids: [BOB_UID],
    updatedAt: new Date(Date.now() - 50),
  });
  removeBatch.update(
    bobDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID),
    {
      memberUids: [BOB_UID],
      adminUids: [BOB_UID],
      updatedAt: new Date(Date.now() - 50),
    }
  );
  removeBatch.delete(
    bobDb.collection("families").doc(FAMILY_ID).collection("members").doc(ALICE_UID)
  );
  await assertSucceeds(removeBatch.commit());
});

test("Security: reject storing passwords in Firestore and invalid member role escalation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Rahman",
      email: "alice@example.com",
      themePreference: "LIGHT",
      passwordHash: "secret_hash",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("families").doc(FAMILY_ID).set(makeFamilyPayload(ALICE_UID, { createdAt: now, updatedAt: now }))
  );

  await assertFails(
    aliceDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("members")
      .doc(ALICE_UID)
      .set(makeAdminMemberPayload(FAMILY_ID, ALICE_UID, "Alice Rahman", { role: "SUPERADMIN" }))
  );
});

test("Auth Security: lock server credential collections, reject forged passkeys, and allow owner session revocation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertFails(aliceDb.collection("auth_credentials").doc(ALICE_UID).get());
  await assertFails(
    aliceDb.collection("auth_credentials").doc(ALICE_UID).set({
      passwordHash: "client_fake",
    })
  );
  await assertFails(aliceDb.collection("phone_index").doc("hash123").get());
  await assertFails(aliceDb.collection("auth_rate_limits").doc("limit123").get());
  await assertFails(aliceDb.collection("webauthn_challenges").doc("ch123").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("trusted_devices").doc("dev_1").set({
      deviceId: "dev_1",
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("passkeys").doc("cred_forged").set({
      credentialId: "cred_forged",
      userId: ALICE_UID,
      label: "Forged Passkey",
      rpId: "example.com",
      publicKeyBase64Url: "AAAA",
      counter: 0,
      createdAt: now,
      lastUsedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_01").set({
      sessionId: "sess_01",
      userId: ALICE_UID,
      deviceId: "dev_pixel_01",
      deviceLabel: "Pixel 9 Pro",
      platform: "Android",
      authMethod: "PHONE_OTP",
      isTrustedDevice: true,
      revoked: false,
      createdAt: now,
      lastActiveAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_01").update({
      revoked: true,
      revokedAt: new Date(Date.now() - 500),
      lastActiveAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_01").update({
      revoked: false,
      lastActiveAt: new Date(Date.now() - 200),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_01").get());
});

test("Normalized Financial Collections: family isolation, required schema fields, and duplicate-key immutability", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // Create family with Alice + Bob (Carol is outside the family)
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminFs = ctx.firestore();
    await adminFs.collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  // 1. Top-level familyMembers directory creation & list query
  await assertSucceeds(
    aliceDb.collection("familyMembers").doc(`${FAMILY_ID}_${BOB_UID}`).set({
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob Rahman",
      role: "MEMBER",
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      relationLabel: "Partner",
      canCreateRecords: true,
      canViewSharedFinances: true,
      canParticipateInChat: true,
      joinedAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    bobDb.collection("familyMembers")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );
  await assertFails(
    carolDb.collection("familyMembers")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );

  // 2. Transaction create + filtered query + cross-family isolation + idempotencyKey immutability
  const txId = "rec_tx_001";
  await assertSucceeds(
    bobDb.collection("transactions").doc(txId).set({
      recordId: txId,
      familyId: FAMILY_ID,
      createdByUserId: BOB_UID,
      amount: 145.50,
      currency: "USD",
      category: "Groceries",
      description: "Weekly household groceries",
      recordStatus: "ACTIVE",
      transactionType: "EXPENSE",
      paymentMethod: "Debit Card",
      occurredAt: now,
      idempotencyKey: "idem_tx_groceries_001",
      syncVersion: 1,
      deviceId: "dev_bob_01",
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Valid filtered query for authorized family member Alice
  await assertSucceeds(
    aliceDb.collection("transactions")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );

  // Unfiltered or outsider Carol read/write must fail
  await assertFails(carolDb.collection("transactions").doc(txId).get());
  await assertFails(
    carolDb.collection("transactions")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );
  await assertFails(
    carolDb.collection("transactions").doc("rec_tx_carol").set({
      recordId: "rec_tx_carol",
      familyId: FAMILY_ID,
      createdByUserId: CAROL_UID,
      amount: 50,
      currency: "USD",
      category: "Misc",
      description: "Unauthorized outsider expense",
      recordStatus: "ACTIVE",
      transactionType: "EXPENSE",
      occurredAt: now,
      idempotencyKey: "idem_carol_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID, CAROL_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Updating transaction amount/status succeeds for creator or admin; changing idempotencyKey fails
  await assertSucceeds(
    bobDb.collection("transactions").doc(txId).update({
      amount: 150.0,
      syncVersion: 2,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("transactions").doc(txId).update({
      idempotencyKey: "tampered_key",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // 3. Loans, Savings, Investments, Budgets validation & family isolation
  await assertSucceeds(
    aliceDb.collection("loans").doc("rec_loan_001").set({
      recordId: "rec_loan_001",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 10000,
      remainingBalance: 7200,
      interestRateApr: 5.5,
      currency: "USD",
      category: "Auto Loan",
      description: "Family SUV financing",
      recordStatus: "ACTIVE",
      loanDirection: "BORROWED",
      counterparty: "Green Credit Union",
      idempotencyKey: "idem_loan_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  // Invalid loan where remainingBalance > principal amount must fail
  await assertFails(
    aliceDb.collection("loans").doc("rec_loan_bad").set({
      recordId: "rec_loan_bad",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 500,
      remainingBalance: 900,
      currency: "USD",
      category: "Loan",
      description: "Invalid balance",
      recordStatus: "ACTIVE",
      loanDirection: "BORROWED",
      counterparty: "Bank",
      idempotencyKey: "idem_loan_bad",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("savings").doc("rec_sav_001").set({
      recordId: "rec_sav_001",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 4200,
      targetAmount: 10000,
      currency: "USD",
      category: "Emergency Fund",
      description: "Six-month family reserve",
      goalName: "Emergency Reserve",
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_sav_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("investments").doc("rec_inv_001").set({
      recordId: "rec_inv_001",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 5000,
      currentValue: 5620,
      units: 24.5,
      currency: "USD",
      category: "Retirement",
      description: "Total market ETF",
      assetName: "Vanguard Total Stock ETF",
      assetType: "ETF",
      symbol: "VTI",
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_inv_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("budgets").doc("rec_bud_001").set({
      recordId: "rec_bud_001",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 800,
      spentAmount: 320,
      alertThresholdPercent: 80,
      currency: "USD",
      category: "Groceries",
      description: "November household grocery cap",
      periodKey: "2026-11",
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_bud_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  // Invalid budget periodKey must fail
  await assertFails(
    aliceDb.collection("budgets").doc("rec_bud_bad").set({
      recordId: "rec_bud_bad",
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 800,
      spentAmount: 0,
      currency: "USD",
      category: "Groceries",
      description: "Bad period",
      periodKey: "Nov-2026",
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_bud_bad",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Chats, Notifications, and Immutable AuditLogs: family enforcement and append-only audit trail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        memberChatEnabled: true,
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  // Chats: member can post and update their own message; outsider denied
  await assertSucceeds(
    bobDb.collection("chats").doc("msg_001").set({
      messageId: "msg_001",
      familyId: FAMILY_ID,
      senderUserId: BOB_UID,
      senderDisplayName: "Bob Rahman",
      messageText: "Uploaded this week's grocery receipt.",
      messageType: "TEXT",
      recordStatus: "ACTIVE",
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(carolDb.collection("chats").doc("msg_001").get());

  // Notifications: recipient can mark acknowledged; outsider denied
  await assertSucceeds(
    aliceDb.collection("notifications").doc("notif_001").set({
      notificationId: "notif_001",
      familyId: FAMILY_ID,
      recipientUserId: BOB_UID,
      createdByUserId: ALICE_UID,
      title: "Budget Threshold Reached",
      body: "Groceries budget passed 80% for 2026-11.",
      notificationType: "BUDGET_ALERT",
      acknowledged: false,
      recordStatus: "ACTIVE",
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    bobDb.collection("notifications").doc("notif_001").update({
      acknowledged: true,
      updatedAt: new Date(Date.now() - 300),
    })
  );
  await assertFails(carolDb.collection("notifications").doc("notif_001").get());

  // AuditLogs: append-only creation succeeds; update and delete strictly denied for everyone
  await assertSucceeds(
    bobDb.collection("auditLogs").doc("aud_001").set({
      auditId: "aud_001",
      familyId: FAMILY_ID,
      actorUserId: BOB_UID,
      entityCollection: "transactions",
      entityId: "rec_tx_001",
      action: "CREATE",
      summary: "Created EXPENSE record for Groceries ($145.50 USD)",
      afterHash: "sha256_sample_hash_001",
      deviceId: "dev_bob_01",
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
    })
  );
  await assertSucceeds(
    aliceDb.collection("auditLogs")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );
  await assertFails(
    aliceDb.collection("auditLogs").doc("aud_001").update({
      summary: "Tampered history",
    })
  );
  await assertFails(aliceDb.collection("auditLogs").doc("aud_001").delete());
  await assertFails(carolDb.collection("auditLogs").doc("aud_001").get());
});

test("Income & Expense CRUD: minor currency units, recurring fields, creator/admin delete & DELETE audit", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const nextMonth = new Date(Date.now() + 30 * 86400 * 1000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  const incomeId = "rec_inc_001";
  await assertSucceeds(
    bobDb.collection("transactions").doc(incomeId).set({
      recordId: incomeId,
      transactionId: incomeId,
      familyId: FAMILY_ID,
      createdByUserId: BOB_UID,
      memberId: BOB_UID,
      amount: 3250.75,
      amountMinor: 325075,
      currency: "USD",
      category: "Salary",
      description: "Monthly engineering payroll",
      recordStatus: "ACTIVE",
      transactionType: "INCOME",
      paymentMethod: "Direct Deposit",
      occurredAt: now,
      recurrenceFrequency: "MONTHLY",
      nextRecurrenceAt: nextMonth,
      isRecurringTemplate: true,
      idempotencyKey: "idem_salary_bob_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider cannot delete Bob's income record
  await assertFails(carolDb.collection("transactions").doc(incomeId).delete());

  // Creator Bob can delete his own transaction and append a DELETE audit entry
  const delBatch = bobDb.batch();
  delBatch.delete(bobDb.collection("transactions").doc(incomeId));
  delBatch.set(bobDb.collection("auditLogs").doc("aud_del_001"), {
    auditId: "aud_del_001",
    familyId: FAMILY_ID,
    actorUserId: BOB_UID,
    entityCollection: "transactions",
    entityId: incomeId,
    action: "DELETE",
    summary: "Deleted INCOME (Salary) $3,250.75",
    memberUids: [ALICE_UID, BOB_UID],
    createdAt: new Date(Date.now() - 200),
  });
  await assertSucceeds(delBatch.commit());
});

test("Prompt 08: Financial modules (loans, savings, investments, budgets) and append-only financialEvents", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const due = new Date(Date.now() + 30 * 86400 * 1000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  // 1. Loan with minor units, borrower/lender info, installments & repayment event
  const loanId = "loan_p08_001";
  await assertSucceeds(
    bobDb.collection("loans").doc(loanId).set({
      recordId: loanId,
      familyId: FAMILY_ID,
      createdByUserId: BOB_UID,
      amount: 1200.0,
      amountMinor: 120000,
      remainingBalance: 1200.0,
      remainingBalanceMinor: 120000,
      interestRateApr: 5.5,
      currency: "USD",
      category: "Personal Loan",
      description: "Family emergency bridge loan",
      recordStatus: "ACTIVE",
      loanDirection: "BORROWED",
      counterparty: "First Credit Union",
      borrowerName: "Bob Family",
      borrowerContact: "+15550001111",
      lenderName: "First Credit Union",
      lenderContact: "support@fcu.example",
      totalInstallments: 12,
      paidInstallments: 0,
      installmentAmountMinor: 10000,
      startDate: now,
      dueDate: due,
      idempotencyKey: "idem_loan_p08_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject invalid remainingBalanceMinor > amountMinor
  await assertFails(
    bobDb.collection("loans").doc(loanId).update({
      remainingBalance: 1300.0,
      remainingBalanceMinor: 130000,
      updatedAt: new Date(),
    })
  );

  // Record repayment update + financialEvent atomically
  const repayBatch = bobDb.batch();
  repayBatch.update(bobDb.collection("loans").doc(loanId), {
    remainingBalance: 1100.0,
    remainingBalanceMinor: 110000,
    paidInstallments: 1,
    syncVersion: 2,
    updatedAt: new Date(Date.now() - 500),
  });
  repayBatch.set(bobDb.collection("financialEvents").doc("evt_repay_001"), {
    eventId: "evt_repay_001",
    familyId: FAMILY_ID,
    parentRecordId: loanId,
    moduleType: "LOANS",
    eventType: "REPAYMENT",
    actorUserId: BOB_UID,
    amountMinor: 10000,
    balanceAfterMinor: 110000,
    currency: "USD",
    note: "Installment #1",
    eventDate: new Date(Date.now() - 500),
    memberUids: [ALICE_UID, BOB_UID],
    createdAt: new Date(Date.now() - 500),
  });
  await assertSucceeds(repayBatch.commit());

  // financialEvents are strictly append-only (no update/delete) and isolated from Carol
  await assertFails(carolDb.collection("financialEvents").doc("evt_repay_001").get());
  await assertFails(bobDb.collection("financialEvents").doc("evt_repay_001").update({ amountMinor: 5000 }));
  await assertFails(aliceDb.collection("financialEvents").doc("evt_repay_001").delete());

  // 2. Savings Goal with minor units & creator delete
  const savId = "sav_p08_001";
  await assertSucceeds(
    bobDb.collection("savings").doc(savId).set({
      recordId: savId,
      familyId: FAMILY_ID,
      createdByUserId: BOB_UID,
      amount: 500.0,
      amountMinor: 50000,
      targetAmount: 2500.0,
      targetAmountMinor: 250000,
      currency: "USD",
      category: "Emergency Fund",
      description: "6-month household reserve",
      goalName: "Emergency Reserve",
      recordStatus: "ACTIVE",
      targetDate: due,
      idempotencyKey: "idem_sav_p08_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // 3. Investment with investedAt and minor values
  const invId = "inv_p08_001";
  await assertSucceeds(
    aliceDb.collection("investments").doc(invId).set({
      recordId: invId,
      familyId: FAMILY_ID,
      createdByUserId: ALICE_UID,
      amount: 2000.0,
      amountMinor: 200000,
      currentValue: 2350.0,
      currentValueMinor: 235000,
      units: 10.5,
      currency: "USD",
      category: "ETF",
      description: "S&P 500 Index",
      assetName: "Vanguard S&P 500",
      assetType: "ETF",
      symbol: "VOO",
      investedAt: now,
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_inv_p08_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // 4. Budget with budgetScope and minor units
  const budId = "bud_p08_001";
  await assertSucceeds(
    bobDb.collection("budgets").doc(budId).set({
      recordId: budId,
      familyId: FAMILY_ID,
      createdByUserId: BOB_UID,
      amount: 800.0,
      amountMinor: 80000,
      spentAmount: 0.0,
      spentAmountMinor: 0,
      alertThresholdPercent: 80,
      currency: "USD",
      category: "Food",
      description: "Monthly groceries cap",
      periodKey: "2026-11",
      budgetScope: "CATEGORY",
      recordStatus: "ACTIVE",
      idempotencyKey: "idem_bud_p08_001",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject invalid budgetScope
  await assertFails(
    bobDb.collection("budgets").doc(budId).update({
      budgetScope: "INVALID_SCOPE",
      updatedAt: new Date(),
    })
  );

  // Outsider Carol cannot delete Bob's loan/savings/budget; Bob can delete his own
  await assertFails(carolDb.collection("loans").doc(loanId).delete());
  await assertSucceeds(bobDb.collection("savings").doc(savId).delete());
  await assertSucceeds(bobDb.collection("budgets").doc(budId).delete());
});

test("Prompt 11 Chat & Notifications: sender membership/chat toggle, edit/soft-delete, read receipts, ALL_MEMBERS alerts & FCM isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 3000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminFs = ctx.firestore();
    await adminFs.collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        memberChatEnabled: true,
        chatRetentionDays: 90,
        createdAt: now,
        updatedAt: now,
      })
    );
    await adminFs.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID).set({
      userId: BOB_UID,
      familyId: FAMILY_ID,
      displayName: "Bob Rahman",
      role: "MEMBER",
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      relationLabel: "Partner",
      canCreateRecords: true,
      canViewSharedFinances: true,
      canParticipateInChat: true,
      joinedAt: now,
      updatedAt: now,
    });
  });

  // 1. Authorized member Bob creates a chat message with readByUids & retention expiry
  const msgId = "msg_p11_001";
  await assertSucceeds(
    bobDb.collection("chats").doc(msgId).set({
      messageId: msgId,
      familyId: FAMILY_ID,
      senderUserId: BOB_UID,
      senderDisplayName: "Bob Rahman",
      senderRoleLabel: "Partner",
      messageText: "Initial grocery budget check-in",
      messageType: "TEXT",
      recordStatus: "ACTIVE",
      readByUids: [BOB_UID],
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider Carol cannot read paginated chat query or send a message
  await assertFails(
    carolDb.collection("chats")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", BOB_UID)
      .orderBy("createdAt", "desc")
      .get()
  );
  await assertFails(
    carolDb.collection("chats").doc("msg_p11_carol").set({
      messageId: "msg_p11_carol",
      familyId: FAMILY_ID,
      senderUserId: CAROL_UID,
      senderDisplayName: "Carol",
      messageText: "Unauthorized",
      messageType: "TEXT",
      recordStatus: "ACTIVE",
      readByUids: [CAROL_UID],
      memberUids: [ALICE_UID, BOB_UID, CAROL_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Member Alice marks Bob's message as read (readByUids update), cannot edit Bob's text
  await assertSucceeds(
    aliceDb.collection("chats").doc(msgId).update({
      readByUids: [BOB_UID, ALICE_UID],
      updatedAt: new Date(Date.now() - 1500),
    })
  );
  await assertFails(
    aliceDb.collection("chats").doc(msgId).update({
      messageText: "Forged text by another member",
      editedAt: new Date(Date.now() - 1200),
      syncVersion: 2,
      updatedAt: new Date(Date.now() - 1200),
    })
  );

  // Sender Bob edits his own message, then Admin Alice soft-deletes (VOIDED)
  await assertSucceeds(
    bobDb.collection("chats").doc(msgId).update({
      messageText: "Updated grocery budget check-in",
      editedAt: new Date(Date.now() - 1000),
      syncVersion: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc(msgId).update({
      messageText: "Message removed",
      recordStatus: "VOIDED",
      deletedAt: new Date(Date.now() - 500),
      deletedByUserId: ALICE_UID,
      syncVersion: 3,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Restricting Bob's canParticipateInChat blocks new message creation
  await assertSucceeds(
    aliceDb.collection("families").doc(FAMILY_ID).collection("members").doc(BOB_UID).update({
      canParticipateInChat: false,
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(
    bobDb.collection("chats").doc("msg_p11_blocked").set({
      messageId: "msg_p11_blocked",
      familyId: FAMILY_ID,
      senderUserId: BOB_UID,
      senderDisplayName: "Bob Rahman",
      messageText: "Should be blocked",
      messageType: "TEXT",
      recordStatus: "ACTIVE",
      readByUids: [BOB_UID],
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // 2. Notification ALL_MEMBERS per-user readByUids update
  await assertSucceeds(
    aliceDb.collection("notifications").doc("ntf_p11_001").set({
      notificationId: "ntf_p11_001",
      familyId: FAMILY_ID,
      recipientUserId: "ALL_MEMBERS",
      createdByUserId: ALICE_UID,
      title: "Savings Milestone 50%",
      body: "Emergency Reserve reached 50% of goal.",
      notificationType: "SAVINGS_MILESTONE",
      deepLinkModule: "SAVINGS",
      acknowledged: false,
      readByUids: [ALICE_UID],
      recordStatus: "ACTIVE",
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    bobDb.collection("notifications").doc("ntf_p11_001").update({
      readByUids: [ALICE_UID, BOB_UID],
      acknowledged: true,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // 3. FCM token isolation under /users/{uid}/fcm_tokens/{deviceId}
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("fcm_tokens").doc("dev_bob_01").set({
      deviceId: "dev_bob_01",
      token: "fcm_token_sample_abcdefghijklmnopqrstuvwxyz",
      platform: "Android",
      notificationsEnabled: true,
      updatedAt: now,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("fcm_tokens").doc("dev_bob_01").get()
  );
});

test("Prompt 11 Calls & Meetings: 1-to-1 signaling isolation, group meet host moderation, and cross-family protection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 3000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminFs = ctx.firestore();
    await adminFs.collection("families").doc(FAMILY_ID).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID, CAROL_UID],
        adminUids: [ALICE_UID],
        createdAt: now,
        updatedAt: now,
      })
    );
    // Notice Dave is outside the family (or test an outsider)
  });
  const daveDb = testEnv.authenticatedContext("uid_dave_outsider").firestore();

  // 1. Alice initiates a 1-to-1 VIDEO call to Bob (Carol is in the family, NOT a participant of Alice<->Bob call!)
  const callId = "call_ab_001";
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).set({
      callId,
      familyId: FAMILY_ID,
      callerUid: ALICE_UID,
      calleeUid: BOB_UID,
      callerName: "Alice",
      calleeName: "Bob",
      participantUids: [ALICE_UID, BOB_UID],
      mediaType: "VIDEO",
      status: "RINGING",
      offerSdp: "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\n",
      offerType: "offer",
      iceRestartCount: 0,
      networkQuality: "UNKNOWN",
      durationSeconds: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Carol (even as a family member) and Dave (outsider) CANNOT read Alice<->Bob's 1-to-1 call or list it
  await assertFails(carolDb.collection("calls").doc(callId).get());
  await assertFails(daveDb.collection("calls").doc(callId).get());
  await assertFails(
    carolDb.collection("calls")
      .where("familyId", "==", FAMILY_ID)
      .where("participantUids", "array-contains", ALICE_UID)
      .get()
  );

  // Caller and callee CAN list their own calls, exchange SDP answer and trickle ICE candidates
  await assertSucceeds(
    bobDb.collection("calls")
      .where("familyId", "==", FAMILY_ID)
      .where("participantUids", "array-contains", BOB_UID)
      .orderBy("createdAt", "desc")
      .get()
  );
  await assertSucceeds(
    bobDb.collection("calls").doc(callId).update({
      status: "ACTIVE",
      answerSdp: "v=0\r\no=- 67890 2 IN IP4 127.0.0.1\r\n",
      answerType: "answer",
      startedAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).collection("iceCandidates").doc("ice_01").set({
      candidateId: "ice_01",
      callId,
      familyId: FAMILY_ID,
      senderUid: ALICE_UID,
      role: "CALLER",
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122260223 192.168.1.10 54321 typ host",
      participantUids: [ALICE_UID, BOB_UID],
      createdAt: new Date(Date.now() - 1800),
    })
  );
  // Carol cannot inject or read ICE candidates on Alice<->Bob's call
  await assertFails(
    carolDb.collection("calls").doc(callId).collection("iceCandidates").doc("ice_01").get()
  );
  await assertFails(
    carolDb.collection("calls").doc(callId).collection("iceCandidates").doc("ice_bad").set({
      candidateId: "ice_bad",
      callId,
      familyId: FAMILY_ID,
      senderUid: CAROL_UID,
      role: "CALLER",
      sdpMLineIndex: 0,
      candidate: "candidate:bad",
      participantUids: [ALICE_UID, BOB_UID],
      createdAt: new Date(Date.now() - 1500),
    })
  );

  // Ending call locks terminal state (no further SDP/status reopening or ICE writes)
  await assertSucceeds(
    bobDb.collection("calls").doc(callId).update({
      status: "ENDED",
      durationSeconds: 42,
      endedByUid: BOB_UID,
      endedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("calls").doc(callId).update({
      status: "ACTIVE",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("calls").doc(callId).collection("iceCandidates").doc("ice_after_end").set({
      candidateId: "ice_after_end",
      callId,
      familyId: FAMILY_ID,
      senderUid: ALICE_UID,
      role: "CALLER",
      sdpMLineIndex: 0,
      candidate: "candidate:late",
      participantUids: [ALICE_UID, BOB_UID],
      createdAt: new Date(Date.now() - 400),
    })
  );

  // 2. Group Family Meet + Participant State + Host Moderation + End Meeting
  const mtgId = "mtg_fam_001";
  await assertSucceeds(
    bobDb.collection("meetings").doc(mtgId).set({
      meetingId: mtgId,
      familyId: FAMILY_ID,
      roomName: `ff_${FAMILY_ID}_${mtgId}`,
      title: "Monthly Family Budget Sync",
      hostUid: BOB_UID,
      hostName: "Bob",
      inviteCode: "FFMEET-9921",
      inviteLink: `https://gen-lang-client-0265273815.web.app/meet?familyId=${FAMILY_ID}&meetingId=${mtgId}&code=FFMEET-9921`,
      status: "LIVE",
      isScheduled: false,
      startedAt: now,
      audioEnabledDefault: true,
      videoEnabledDefault: true,
      invitedUids: [ALICE_UID, BOB_UID, CAROL_UID],
      activeParticipantUids: [BOB_UID],
      memberUids: [ALICE_UID, BOB_UID, CAROL_UID],
      adminUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider Dave cannot list or read family meetings
  await assertFails(daveDb.collection("meetings").doc(mtgId).get());
  await assertFails(
    daveDb.collection("meetings")
      .where("familyId", "==", FAMILY_ID)
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );

  // Family member Carol joins meeting participant subcollection, cannot escalate mutedByHost/removedByHost
  await assertSucceeds(
    carolDb.collection("meetings").doc(mtgId).collection("participants").doc(CAROL_UID).set({
      participantUid: CAROL_UID,
      meetingId: mtgId,
      familyId: FAMILY_ID,
      displayName: "Carol",
      isHost: false,
      state: "JOINED",
      audioEnabled: true,
      videoEnabled: true,
      screenSharing: false,
      mutedByHost: false,
      removedByHost: false,
      memberUids: [ALICE_UID, BOB_UID, CAROL_UID],
      joinedAt: now,
      updatedAt: now,
    })
  );

  // Host Bob mutes Carol and then removes Carol
  await assertSucceeds(
    bobDb.collection("meetings").doc(mtgId).collection("participants").doc(CAROL_UID).update({
      mutedByHost: true,
      audioEnabled: false,
      updatedAt: new Date(Date.now() - 800),
    })
  );
  // Muted participant Carol CANNOT unmute herself while mutedByHost == true
  await assertFails(
    carolDb.collection("meetings").doc(mtgId).collection("participants").doc(CAROL_UID).update({
      audioEnabled: true,
      updatedAt: new Date(Date.now() - 700),
    })
  );
  // Host Bob removes Carol; non-host Carol cannot end meeting for everyone
  await assertFails(
    carolDb.collection("meetings").doc(mtgId).update({
      status: "ENDED",
      endedByUid: CAROL_UID,
      updatedAt: new Date(Date.now() - 600),
    })
  );
  await assertSucceeds(
    bobDb.collection("meetings").doc(mtgId).collection("participants").doc(CAROL_UID).update({
      state: "REMOVED",
      removedByHost: true,
      audioEnabled: false,
      videoEnabled: false,
      leftAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  // Host Bob ends meeting for everyone
  await assertSucceeds(
    bobDb.collection("meetings").doc(mtgId).update({
      status: "ENDED",
      endedByUid: BOB_UID,
      endedAt: new Date(Date.now() - 300),
      activeParticipantUids: [],
      updatedAt: new Date(Date.now() - 300),
    })
  );
  await assertFails(
    bobDb.collection("meetings").doc(mtgId).update({
      status: "LIVE",
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Prompt 13 Security: Personal workspace isolation, suspendedUids blocking, server secrets lock & membership_notices", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const personalAliceId = `personal_${ALICE_UID}`;
  const sharedFam12 = "A1B2C3D4E5F6";

  // 1. Personal Finance workspace is strictly owner-only
  await assertSucceeds(
    aliceDb.collection("families").doc(personalAliceId).set(
      makeFamilyPayload(ALICE_UID, {
        name: "Personal Finance",
        isPersonal: true,
        requireAdminApproval: false,
        memberChatEnabled: false,
        createdAt: now,
        updatedAt: now,
      })
    )
  );
  await assertFails(
    bobDb.collection("families").doc(personalAliceId).get()
  );
  await assertFails(
    bobDb.collection("families").doc(`personal_${ALICE_UID}`).set(
      makeFamilyPayload(BOB_UID, { isPersonal: true, createdAt: now, updatedAt: now })
    )
  );

  // 2. Server-only secrets & phone_index deny all client reads and writes
  await assertFails(aliceDb.collection("family_secrets").doc(sharedFam12).get());
  await assertFails(
    aliceDb.collection("family_secrets").doc(sharedFam12).set({ passwordHash: "tampered" })
  );
  await assertFails(aliceDb.collection("phone_index").doc("+8801711223344").get());

  // 3. Shared 12-char family with suspended member Bob blocks Bob from reads & writes
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(sharedFam12).set(
      makeFamilyPayload(ALICE_UID, {
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        suspendedUids: [BOB_UID],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  await assertSucceeds(aliceDb.collection("families").doc(sharedFam12).get());
  await assertFails(bobDb.collection("families").doc(sharedFam12).get());
  await assertFails(
    bobDb.collection("transactions").doc("tx_suspended_bob").set({
      recordId: "tx_suspended_bob",
      familyId: sharedFam12,
      createdByUserId: BOB_UID,
      amount: 10,
      currency: "BDT",
      category: "Misc",
      description: "Blocked while suspended",
      recordStatus: "ACTIVE",
      transactionType: "EXPENSE",
      occurredAt: now,
      idempotencyKey: "idem_susp_bob",
      syncVersion: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // 4. Owner-only membership_notices: Admin/Server seeds notice, Bob can read & delete own notice, Carol cannot
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore()
      .collection("users").doc(BOB_UID)
      .collection("membership_notices").doc(sharedFam12)
      .set({
        familyId: sharedFam12,
        familyName: "Rahman Household",
        status: "SUSPENDED",
        reason: "Temporarily paused by Primary Admin",
        updatedAt: now,
      });
  });

  await assertFails(carolDb.collection("users").doc(BOB_UID).collection("membership_notices").doc(sharedFam12).get());
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("membership_notices").doc("FAKE_ID").set({
      familyId: "FAKE_ID",
      status: "SUSPENDED",
    })
  );
  await assertSucceeds(bobDb.collection("users").doc(BOB_UID).collection("membership_notices").doc(sharedFam12).get());
  await assertSucceeds(bobDb.collection("users").doc(BOB_UID).collection("membership_notices").doc(sharedFam12).delete());
});

test("Prompt 14 Security: Digital Member Card 16-char ID validation, PII exclusion, privacy isolation & cross-family denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const famId = "FAM14CARD123";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(famId).set(
      makeFamilyPayload(ALICE_UID, {
        name: "Hasan Family",
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        suspendedUids: [],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  const baseCard = (uid, cardMemberId, role = "MEMBER") => ({
    userId: uid,
    familyId: famId,
    cardMemberId,
    familyName: "Hasan Family",
    displayName: uid === ALICE_UID ? "Alice Hasan" : "Bob Hasan",
    role,
    memberStatus: "ACTIVE",
    memberUids: [ALICE_UID, BOB_UID],
    adminUids: [ALICE_UID],
    showAvailableBalance: true,
    showIncomeAndExpense: true,
    showFamilyContributions: true,
    showLoans: true,
    showSavings: true,
    showInvestments: true,
    showTransactionHistory: true,
    allowAdminDetailedAudit: true,
    joinedAt: now,
    updatedAt: now,
  });

  // Invalid cardMemberId (not 16 uppercase alphanumeric) is rejected
  await assertFails(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(ALICE_UID).set(
      baseCard(ALICE_UID, "SHORT123", "ADMIN")
    )
  );

  // PII fields like dateOfBirth, phoneNumber, maskedPhone, passwordHash are strictly rejected
  await assertFails(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(ALICE_UID).set({
      ...baseCard(ALICE_UID, "ABCD1234EFGH5678", "ADMIN"),
      dateOfBirth: "1992-04-12",
    })
  );
  await assertFails(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(ALICE_UID).set({
      ...baseCard(ALICE_UID, "ABCD1234EFGH5678", "ADMIN"),
      phoneNumber: "+8801711223344",
    })
  );

  // Valid Member Cards succeed for Alice and Bob
  await assertSucceeds(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(ALICE_UID).set(
      baseCard(ALICE_UID, "ABCD1234EFGH5678", "ADMIN")
    )
  );
  await assertSucceeds(
    bobDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).set(
      baseCard(BOB_UID, "WXYZ9876MNPQ5432", "MEMBER")
    )
  );

  // Family member Alice can read Bob's card, while non-member Carol is denied
  await assertSucceeds(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).get()
  );
  await assertFails(
    carolDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).get()
  );
  await assertFails(
    carolDb.collection("families").doc(famId).collection("member_cards").get()
  );

  // Bob can update his own privacy flags, but CANNOT mutate his role or cardMemberId
  await assertSucceeds(
    bobDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).update({
      showAvailableBalance: false,
      showTransactionHistory: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).update({
      role: "ADMIN",
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(
    bobDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).update({
      cardMemberId: "NEWID12345678901",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Even Admin Alice cannot overwrite Bob's personal privacy preferences directly
  await assertFails(
    aliceDb.collection("families").doc(famId).collection("member_cards").doc(BOB_UID).update({
      showAvailableBalance: true,
      updatedAt: new Date(Date.now() - 300),
    })
  );
});

test("Prompt 15 Security: Approval settings admin-only, proposal vote anti-tampering, vote-change policy & once-only execution", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const carolDb = testEnv.authenticatedContext(CAROL_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const future = new Date(Date.now() + 86400_000);
  const famId = "FAM15DECISION";
  const propId = "prop_solar_001";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("families").doc(famId).set(
      makeFamilyPayload(ALICE_UID, {
        name: "Chowdhury Household",
        memberUids: [ALICE_UID, BOB_UID],
        adminUids: [ALICE_UID],
        suspendedUids: [],
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  // 1. Only Admin Alice can configure approval_settings; Member Bob cannot; Carol cannot read
  const settingsPayload = {
    configId: "default",
    familyId: famId,
    ruleMode: "UNANIMOUS",
    minApprovalPercent: 100,
    minQuorumCount: 2,
    defaultDeadlineHours: 72,
    allowCreatorVote: true,
    allowVoteChangeBeforeDeadline: false,
    eligibleVoterRule: "ALL_ACTIVE_MEMBERS",
    specificEligibleUids: [],
    requiredCategories: ["TAKE_LOAN", "MAKE_INVESTMENT", "WITHDRAW_SAVINGS"],
    largeExpenseThresholdMinor: 500000,
    updatedByUid: ALICE_UID,
    createdAt: now,
    updatedAt: now,
  };
  await assertSucceeds(
    aliceDb.collection("families").doc(famId).collection("approval_settings").doc("default").set(settingsPayload)
  );
  await assertFails(
    bobDb.collection("families").doc(famId).collection("approval_settings").doc("default").set({
      ...settingsPayload,
      updatedByUid: BOB_UID,
    })
  );
  await assertFails(
    carolDb.collection("families").doc(famId).collection("approval_settings").doc("default").get()
  );

  // 2. Alice creates a PENDING proposal
  const proposalDoc = {
    proposalId: propId,
    familyId: famId,
    title: "Solar Battery Backup",
    category: "LARGE_PURCHASE",
    description: "Hybrid inverter and LiFePO4 pack",
    reason: "Outage resilience",
    amountMinor: 1200000,
    currency: "BDT",
    relatedParty: "Dhaka Solar Co",
    proposedDate: now,
    votingDeadline: future,
    proposerUid: ALICE_UID,
    proposerName: "Alice",
    status: "PENDING",
    ruleMode: "UNANIMOUS",
    minApprovalPercent: 100,
    minQuorumRequired: 2,
    allowCreatorVote: true,
    allowVoteChangeBeforeDeadline: false,
    eligibleVoterRule: "ALL_ACTIVE_MEMBERS",
    eligibleVoterUids: [ALICE_UID, BOB_UID],
    approvedCount: 0,
    rejectedCount: 0,
    finalDecision: null,
    memberUids: [ALICE_UID, BOB_UID],
    adminUids: [ALICE_UID],
    createdAt: now,
    updatedAt: now,
  };
  await assertSucceeds(aliceDb.collection("proposals").doc(propId).set(proposalDoc));

  // Cross-family Carol cannot read proposal
  await assertFails(carolDb.collection("proposals").doc(propId).get());

  // 3. Anti-tamper: Admin Alice CANNOT secretly force status='APPROVED' without required unanimous tally
  await assertFails(
    aliceDb.runTransaction(async (tx) => {
      tx.set(aliceDb.collection("proposals").doc(propId).collection("votes").doc(ALICE_UID), {
        voterUid: ALICE_UID,
        voterName: "Alice",
        proposalId: propId,
        familyId: famId,
        choice: "APPROVE",
        memberUids: [ALICE_UID, BOB_UID],
        votedAt: now,
        updatedAt: now,
      });
      tx.update(aliceDb.collection("proposals").doc(propId), {
        approvedCount: 1,
        rejectedCount: 0,
        status: "APPROVED",
        updatedAt: now,
      });
    })
  );

  // Valid first vote keeps status=PENDING
  await assertSucceeds(
    aliceDb.runTransaction(async (tx) => {
      tx.set(aliceDb.collection("proposals").doc(propId).collection("votes").doc(ALICE_UID), {
        voterUid: ALICE_UID,
        voterName: "Alice",
        proposalId: propId,
        familyId: famId,
        choice: "APPROVE",
        memberUids: [ALICE_UID, BOB_UID],
        votedAt: now,
        updatedAt: now,
      });
      tx.update(aliceDb.collection("proposals").doc(propId), {
        approvedCount: 1,
        rejectedCount: 0,
        status: "PENDING",
        updatedAt: now,
      });
    })
  );

  // Vote change is blocked when allowVoteChangeBeforeDeadline == false
  await assertFails(
    aliceDb.collection("proposals").doc(propId).collection("votes").doc(ALICE_UID).update({
      choice: "REJECT",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob casts the second APPROVE vote -> APPROVED succeeds atomically
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      tx.set(bobDb.collection("proposals").doc(propId).collection("votes").doc(BOB_UID), {
        voterUid: BOB_UID,
        voterName: "Bob",
        proposalId: propId,
        familyId: famId,
        choice: "APPROVE",
        memberUids: [ALICE_UID, BOB_UID],
        votedAt: now,
        updatedAt: now,
      });
      tx.update(bobDb.collection("proposals").doc(propId), {
        approvedCount: 2,
        rejectedCount: 0,
        status: "APPROVED",
        finalDecision: "APPROVED",
        updatedAt: now,
      });
    })
  );

  // 4. Exact-once execution: first APPROVED -> EXECUTED succeeds, second attempt fails
  await assertSucceeds(
    aliceDb.collection("proposals").doc(propId).update({
      status: "EXECUTED",
      finalDecision: "EXECUTED",
      executedRecordId: "rec_proposal_exec_prop_solar_001",
      executedCollection: "transactions",
      executedByUid: ALICE_UID,
      executedAt: new Date(Date.now() - 400),
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(
    bobDb.collection("proposals").doc(propId).update({
      status: "EXECUTED",
      executedRecordId: "rec_duplicate",
      executedCollection: "transactions",
      executedByUid: BOB_UID,
      executedAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );
});







