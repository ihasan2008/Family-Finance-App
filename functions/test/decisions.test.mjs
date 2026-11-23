import test from "node:test";
import assert from "node:assert/strict";
import {
  buildExecutionDescriptor,
  evaluateProposalTally,
  resolveEligibleVoters,
  validateApprovalRulesConfig,
} from "../src/decisions.ts";

test("resolveEligibleVoters respects rules and creator voting exclusion", () => {
  const members = [
    { userId: "alice", role: "ADMIN", canCreateRecords: true, isSuspended: false },
    { userId: "bob", role: "MEMBER", canCreateRecords: true, isSuspended: false },
    { userId: "carol", role: "MEMBER", canCreateRecords: false, isSuspended: false },
    { userId: "dave", role: "MEMBER", canCreateRecords: true, isSuspended: true },
  ];

  const allAllowed = resolveEligibleVoters({
    members,
    proposerUid: "alice",
    eligibleVoterRule: "ALL_ACTIVE_MEMBERS",
    specificEligibleUids: [],
    allowCreatorVote: true,
  });
  assert.deepEqual(allAllowed, ["alice", "bob", "carol"]);

  const noCreator = resolveEligibleVoters({
    members,
    proposerUid: "alice",
    eligibleVoterRule: "ALL_ACTIVE_MEMBERS",
    specificEligibleUids: [],
    allowCreatorVote: false,
  });
  assert.deepEqual(noCreator, ["bob", "carol"]);

  const recordAuthOnly = resolveEligibleVoters({
    members,
    proposerUid: "alice",
    eligibleVoterRule: "RECORDS_AUTHORIZED_MEMBERS",
    specificEligibleUids: [],
    allowCreatorVote: true,
  });
  assert.deepEqual(recordAuthOnly, ["alice", "bob"]);

  const adminsOnly = resolveEligibleVoters({
    members,
    proposerUid: "alice",
    eligibleVoterRule: "ADMINS_ONLY",
    specificEligibleUids: [],
    allowCreatorVote: true,
  });
  assert.deepEqual(adminsOnly, ["alice"]);
});

test("Majority approval requires strict majority of eligible voters and quorum", () => {
  const now = 1_700_000_000_000;
  const deadline = now + 86_400_000;

  const oneApprove = evaluateProposalTally({
    eligibleVoterUids: ["alice", "bob", "carol"],
    votes: [{ voterUid: "alice", choice: "APPROVE" }],
    ruleMode: "MAJORITY",
    minApprovalPercent: 51,
    minQuorumRequired: 2,
    votingDeadlineMs: deadline,
    nowMs: now,
    currentStatus: "PENDING",
  });
  assert.equal(oneApprove.nextStatus, "PENDING");
  assert.equal(oneApprove.approvedCount, 1);
  assert.equal(oneApprove.pendingCount, 2);

  const twoApprove = evaluateProposalTally({
    eligibleVoterUids: ["alice", "bob", "carol"],
    votes: [
      { voterUid: "alice", choice: "APPROVE" },
      { voterUid: "bob", choice: "APPROVE" },
    ],
    ruleMode: "MAJORITY",
    minApprovalPercent: 51,
    minQuorumRequired: 2,
    votingDeadlineMs: deadline,
    nowMs: now,
    currentStatus: "PENDING",
  });
  assert.equal(twoApprove.nextStatus, "APPROVED");
  assert.equal(twoApprove.finalDecision, "APPROVED");
  assert.equal(twoApprove.approvalPercentOfEligible, 67);
});

test("Unanimous approval rejects immediately when any eligible voter rejects", () => {
  const now = 1_700_000_000_000;
  const deadline = now + 86_400_000;

  const rejected = evaluateProposalTally({
    eligibleVoterUids: ["alice", "bob", "carol"],
    votes: [
      { voterUid: "alice", choice: "APPROVE" },
      { voterUid: "bob", choice: "REJECT" },
    ],
    ruleMode: "UNANIMOUS",
    minApprovalPercent: 100,
    minQuorumRequired: 1,
    votingDeadlineMs: deadline,
    nowMs: now,
    currentStatus: "PENDING",
  });
  assert.equal(rejected.nextStatus, "REJECTED");
  assert.equal(rejected.finalDecision, "REJECTED");
});

test("Minimum percentage and deadline expiration behave deterministically", () => {
  const now = 1_700_000_000_000;
  const deadline = now + 10_000;

  const minPct80 = evaluateProposalTally({
    eligibleVoterUids: ["u1", "u2", "u3", "u4", "u5"],
    votes: [
      { voterUid: "u1", choice: "APPROVE" },
      { voterUid: "u2", choice: "APPROVE" },
      { voterUid: "u3", choice: "APPROVE" },
    ],
    ruleMode: "MIN_PERCENTAGE",
    minApprovalPercent: 80,
    minQuorumRequired: 2,
    votingDeadlineMs: deadline,
    nowMs: now,
    currentStatus: "PENDING",
  });
  assert.equal(minPct80.requiredApprovals, 4);
  assert.equal(minPct80.nextStatus, "PENDING");

  const expired = evaluateProposalTally({
    eligibleVoterUids: ["u1", "u2", "u3", "u4", "u5"],
    votes: [{ voterUid: "u1", choice: "APPROVE" }],
    ruleMode: "MIN_PERCENTAGE",
    minApprovalPercent: 80,
    minQuorumRequired: 2,
    votingDeadlineMs: deadline,
    nowMs: deadline + 1,
    currentStatus: "PENDING",
  });
  assert.equal(expired.nextStatus, "EXPIRED");
  assert.equal(expired.finalDecision, "EXPIRED");
});

test("Duplicate votes for the same voterUid are deduplicated cleanly", () => {
  const now = 1_700_000_000_000;
  const tally = evaluateProposalTally({
    eligibleVoterUids: ["alice", "bob"],
    votes: [
      { voterUid: "alice", choice: "APPROVE" },
      { voterUid: "alice", choice: "APPROVE" },
    ],
    ruleMode: "UNANIMOUS",
    minApprovalPercent: 100,
    minQuorumRequired: 2,
    votingDeadlineMs: now + 1000,
    nowMs: now,
    currentStatus: "PENDING",
  });
  assert.equal(tally.approvedCount, 1);
  assert.equal(tally.totalCast, 1);
  assert.equal(tally.pendingCount, 1);
  assert.equal(tally.nextStatus, "PENDING");
});

test("validateApprovalRulesConfig rejects out-of-range inputs", () => {
  assert.throws(() => validateApprovalRulesConfig({ minApprovalPercent: 101 }));
  assert.throws(() => validateApprovalRulesConfig({ minQuorumCount: 0 }));
  assert.throws(() => validateApprovalRulesConfig({ largeExpenseThresholdMinor: -5 }));
});

test("buildExecutionDescriptor maps all 7 categories cleanly", () => {
  assert.equal(buildExecutionDescriptor("p1", "TAKE_LOAN").loanDirection, "BORROWED");
  assert.equal(buildExecutionDescriptor("p2", "GIVE_LOAN").loanDirection, "LENT");
  assert.equal(buildExecutionDescriptor("p3", "MAKE_INVESTMENT").targetCollection, "investments");
  assert.equal(buildExecutionDescriptor("p4", "WITHDRAW_SAVINGS").targetCollection, "savings");
  assert.equal(buildExecutionDescriptor("p5", "LARGE_EXPENSE").targetCollection, "transactions");
  assert.equal(buildExecutionDescriptor("p6", "LARGE_PURCHASE").targetCollection, "transactions");
  assert.equal(buildExecutionDescriptor("p7", "OTHER_CONFIGURABLE").idempotencyKey, "proposal_exec_p7");
});
