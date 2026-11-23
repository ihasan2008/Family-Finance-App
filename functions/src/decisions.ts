export const DECISION_CATEGORIES = [
  "TAKE_LOAN",
  "GIVE_LOAN",
  "MAKE_INVESTMENT",
  "WITHDRAW_SAVINGS",
  "LARGE_EXPENSE",
  "LARGE_PURCHASE",
  "OTHER_CONFIGURABLE",
] as const;

export type DecisionCategory = (typeof DECISION_CATEGORIES)[number];

export const RULE_MODES = ["MAJORITY", "UNANIMOUS", "MIN_PERCENTAGE"] as const;
export type ApprovalRuleMode = (typeof RULE_MODES)[number];

export const ELIGIBLE_VOTER_RULES = [
  "ALL_ACTIVE_MEMBERS",
  "RECORDS_AUTHORIZED_MEMBERS",
  "ADMINS_ONLY",
  "SPECIFIC_MEMBERS",
] as const;
export type EligibleVoterRule = (typeof ELIGIBLE_VOTER_RULES)[number];

export const PROPOSAL_STATUSES = [
  "DRAFT",
  "PENDING",
  "APPROVED",
  "REJECTED",
  "EXPIRED",
  "CANCELLED",
  "EXECUTED",
] as const;
export type ProposalStatus = (typeof PROPOSAL_STATUSES)[number];

export const VOTE_CHOICES = ["APPROVE", "REJECT"] as const;
export type VoteChoice = (typeof VOTE_CHOICES)[number];

export interface ApprovalRulesConfig {
  ruleMode: ApprovalRuleMode;
  minApprovalPercent: number;
  minQuorumCount: number;
  defaultDeadlineHours: number;
  allowCreatorVote: boolean;
  allowVoteChangeBeforeDeadline: boolean;
  eligibleVoterRule: EligibleVoterRule;
  specificEligibleUids: string[];
  requiredCategories: DecisionCategory[];
  largeExpenseThresholdMinor: number;
}

export interface FamilyMemberVoteEligibilityInput {
  userId: string;
  role: string;
  canCreateRecords: boolean;
  isSuspended?: boolean;
}

export interface ProposalVoteInput {
  voterUid: string;
  choice: VoteChoice;
}

export interface TallyEvaluationResult {
  totalEligible: number;
  effectiveQuorum: number;
  requiredApprovals: number;
  approvedCount: number;
  rejectedCount: number;
  pendingCount: number;
  totalCast: number;
  approvalPercentOfEligible: number;
  approvalPercentOfCast: number;
  nextStatus: ProposalStatus;
  finalDecision: string | null;
}

export const DEFAULT_APPROVAL_RULES: ApprovalRulesConfig = {
  ruleMode: "MAJORITY",
  minApprovalPercent: 60,
  minQuorumCount: 1,
  defaultDeadlineHours: 72,
  allowCreatorVote: true,
  allowVoteChangeBeforeDeadline: true,
  eligibleVoterRule: "ALL_ACTIVE_MEMBERS",
  specificEligibleUids: [],
  requiredCategories: [...DECISION_CATEGORIES],
  largeExpenseThresholdMinor: 500000, // 5,000.00 BDT
};

export function validateApprovalRulesConfig(raw: Partial<ApprovalRulesConfig>): ApprovalRulesConfig {
  const ruleMode = RULE_MODES.includes(raw.ruleMode as ApprovalRuleMode)
    ? (raw.ruleMode as ApprovalRuleMode)
    : "MAJORITY";

  const minApprovalPercent = Math.trunc(Number(raw.minApprovalPercent ?? 60));
  if (!Number.isFinite(minApprovalPercent) || minApprovalPercent < 1 || minApprovalPercent > 100) {
    throw Object.assign(new Error("Minimum approval percentage must be between 1 and 100."), {
      statusCode: 400,
      code: "INVALID_APPROVAL_PERCENT",
    });
  }

  const minQuorumCount = Math.trunc(Number(raw.minQuorumCount ?? 1));
  if (!Number.isFinite(minQuorumCount) || minQuorumCount < 1 || minQuorumCount > 20) {
    throw Object.assign(new Error("Minimum quorum count must be between 1 and 20."), {
      statusCode: 400,
      code: "INVALID_QUORUM",
    });
  }

  const defaultDeadlineHours = Math.trunc(Number(raw.defaultDeadlineHours ?? 72));
  if (!Number.isFinite(defaultDeadlineHours) || defaultDeadlineHours < 1 || defaultDeadlineHours > 720) {
    throw Object.assign(new Error("Default voting deadline must be between 1 and 720 hours."), {
      statusCode: 400,
      code: "INVALID_DEADLINE_HOURS",
    });
  }

  const eligibleVoterRule = ELIGIBLE_VOTER_RULES.includes(raw.eligibleVoterRule as EligibleVoterRule)
    ? (raw.eligibleVoterRule as EligibleVoterRule)
    : "ALL_ACTIVE_MEMBERS";

  const specificEligibleUids = Array.isArray(raw.specificEligibleUids)
    ? Array.from(new Set(raw.specificEligibleUids.map((u) => String(u).trim()).filter(Boolean))).slice(0, 20)
    : [];

  const requiredCategories = Array.isArray(raw.requiredCategories)
    ? (raw.requiredCategories.filter((c) => DECISION_CATEGORIES.includes(c as DecisionCategory)) as DecisionCategory[])
    : [...DECISION_CATEGORIES];

  const largeExpenseThresholdMinor = Math.trunc(Number(raw.largeExpenseThresholdMinor ?? 500000));
  if (!Number.isFinite(largeExpenseThresholdMinor) || largeExpenseThresholdMinor < 0) {
    throw Object.assign(new Error("Large expense threshold must be zero or greater."), {
      statusCode: 400,
      code: "INVALID_THRESHOLD",
    });
  }

  return {
    ruleMode,
    minApprovalPercent,
    minQuorumCount,
    defaultDeadlineHours,
    allowCreatorVote: raw.allowCreatorVote !== false,
    allowVoteChangeBeforeDeadline: raw.allowVoteChangeBeforeDeadline !== false,
    eligibleVoterRule,
    specificEligibleUids,
    requiredCategories,
    largeExpenseThresholdMinor,
  };
}

export function resolveEligibleVoters(params: {
  members: FamilyMemberVoteEligibilityInput[];
  proposerUid: string;
  eligibleVoterRule: EligibleVoterRule;
  specificEligibleUids: string[];
  allowCreatorVote: boolean;
}): string[] {
  const { members, proposerUid, eligibleVoterRule, specificEligibleUids, allowCreatorVote } = params;
  const activeMembers = members.filter((m) => m.userId && !m.isSuspended);
  const specificSet = new Set(specificEligibleUids);

  const filtered = activeMembers.filter((m) => {
    switch (eligibleVoterRule) {
      case "ADMINS_ONLY":
        return m.role === "ADMIN";
      case "RECORDS_AUTHORIZED_MEMBERS":
        return m.role === "ADMIN" || m.canCreateRecords;
      case "SPECIFIC_MEMBERS":
        return specificSet.has(m.userId);
      case "ALL_ACTIVE_MEMBERS":
      default:
        return true;
    }
  });

  const uniqueUids = Array.from(new Set(filtered.map((m) => m.userId)));
  if (!allowCreatorVote) {
    return uniqueUids.filter((uid) => uid !== proposerUid);
  }
  return uniqueUids;
}

export function requiredApprovalsForRule(
  eligibleCount: number,
  ruleMode: ApprovalRuleMode,
  minApprovalPercent: number,
  effectiveQuorum: number
): number {
  if (eligibleCount <= 0) return 1;
  switch (ruleMode) {
    case "UNANIMOUS":
      return eligibleCount;
    case "MIN_PERCENTAGE": {
      const pctRequired = Math.max(1, Math.ceil((eligibleCount * minApprovalPercent) / 100));
      return Math.max(effectiveQuorum, pctRequired);
    }
    case "MAJORITY":
    default: {
      const strictMajority = Math.floor(eligibleCount / 2) + 1;
      return Math.max(effectiveQuorum, strictMajority);
    }
  }
}

export function evaluateProposalTally(params: {
  eligibleVoterUids: string[];
  votes: ProposalVoteInput[];
  ruleMode: ApprovalRuleMode;
  minApprovalPercent: number;
  minQuorumRequired: number;
  votingDeadlineMs: number;
  nowMs: number;
  currentStatus: ProposalStatus;
  existingFinalDecision?: string | null;
}): TallyEvaluationResult {
  const {
    eligibleVoterUids,
    votes,
    ruleMode,
    minApprovalPercent,
    minQuorumRequired,
    votingDeadlineMs,
    nowMs,
    currentStatus,
    existingFinalDecision = null,
  } = params;

  const eligibleSet = new Set(eligibleVoterUids);
  const totalEligible = eligibleSet.size;
  const deduped = new Map<string, VoteChoice>();
  for (const vote of votes) {
    if (eligibleSet.has(vote.voterUid) && (vote.choice === "APPROVE" || vote.choice === "REJECT")) {
      deduped.set(vote.voterUid, vote.choice);
    }
  }

  let approvedCount = 0;
  let rejectedCount = 0;
  for (const choice of deduped.values()) {
    if (choice === "APPROVE") approvedCount += 1;
    else if (choice === "REJECT") rejectedCount += 1;
  }

  const totalCast = approvedCount + rejectedCount;
  const pendingCount = Math.max(0, totalEligible - totalCast);
  const effectiveQuorum = totalEligible === 0 ? 1 : Math.min(Math.max(1, minQuorumRequired), totalEligible);
  const requiredApprovals = requiredApprovalsForRule(
    totalEligible,
    ruleMode,
    minApprovalPercent,
    effectiveQuorum
  );

  const approvalPercentOfEligible =
    totalEligible === 0 ? 0 : Math.round((approvedCount * 100) / totalEligible);
  const approvalPercentOfCast =
    totalCast === 0 ? 0 : Math.round((approvedCount * 100) / totalCast);

  let nextStatus: ProposalStatus = currentStatus;
  let finalDecision: string | null = existingFinalDecision;

  if (currentStatus === "PENDING") {
    if (nowMs > votingDeadlineMs) {
      nextStatus = "EXPIRED";
      finalDecision = "EXPIRED";
    } else if (totalEligible === 0) {
      nextStatus = "PENDING";
      finalDecision = null;
    } else if (ruleMode === "UNANIMOUS") {
      if (rejectedCount > 0) {
        nextStatus = "REJECTED";
        finalDecision = "REJECTED";
      } else if (approvedCount === totalEligible && approvedCount >= effectiveQuorum) {
        nextStatus = "APPROVED";
        finalDecision = "APPROVED";
      } else {
        nextStatus = "PENDING";
        finalDecision = null;
      }
    } else {
      if (approvedCount >= requiredApprovals && totalCast >= effectiveQuorum) {
        nextStatus = "APPROVED";
        finalDecision = "APPROVED";
      } else if (
        approvedCount + pendingCount < requiredApprovals ||
        (pendingCount === 0 && (totalCast < effectiveQuorum || approvedCount < requiredApprovals))
      ) {
        nextStatus = "REJECTED";
        finalDecision = "REJECTED";
      } else {
        nextStatus = "PENDING";
        finalDecision = null;
      }
    }
  }

  return {
    totalEligible,
    effectiveQuorum,
    requiredApprovals,
    approvedCount,
    rejectedCount,
    pendingCount,
    totalCast,
    approvalPercentOfEligible,
    approvalPercentOfCast,
    nextStatus,
    finalDecision,
  };
}

export interface ExecutionDescriptor {
  targetCollection: "loans" | "investments" | "savings" | "transactions";
  loanDirection?: "BORROWED" | "LENT";
  transactionType?: "EXPENSE";
  idempotencyKey: string;
}

export function buildExecutionDescriptor(proposalId: string, category: DecisionCategory): ExecutionDescriptor {
  const idempotencyKey = `proposal_exec_${proposalId}`;
  switch (category) {
    case "TAKE_LOAN":
      return { targetCollection: "loans", loanDirection: "BORROWED", idempotencyKey };
    case "GIVE_LOAN":
      return { targetCollection: "loans", loanDirection: "LENT", idempotencyKey };
    case "MAKE_INVESTMENT":
      return { targetCollection: "investments", idempotencyKey };
    case "WITHDRAW_SAVINGS":
      return { targetCollection: "savings", idempotencyKey };
    case "LARGE_EXPENSE":
    case "LARGE_PURCHASE":
    case "OTHER_CONFIGURABLE":
    default:
      return { targetCollection: "transactions", transactionType: "EXPENSE", idempotencyKey };
  }
}

export function assertValidCancelReason(reason: unknown): string {
  const trimmed = String(reason ?? "").trim();
  if (trimmed.length < 5) {
    throw Object.assign(new Error("A cancellation reason of at least 5 characters is required."), {
      statusCode: 400,
      code: "CANCEL_REASON_REQUIRED",
    });
  }
  return trimmed.slice(0, 300);
}
