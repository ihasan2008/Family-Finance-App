package com.example.core.model

import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MemberSortOption(val label: String) {
    ROLE_ADMIN_FIRST("Admin First"),
    NAME_ASC("Name (A–Z)"),
    BALANCE_DESC("Available Balance"),
    JOIN_DATE_DESC("Newest Joined"),
    STATUS_ACTIVE_FIRST("Active Status")
}

enum class MemberLedgerItemType(val label: String) {
    INCOME("Income"),
    EXPENSE("Expense"),
    LOAN("Loan"),
    SAVINGS("Savings"),
    INVESTMENT("Investment"),
    FAMILY_CONTRIBUTION("Family Contribution")
}

enum class MemberLedgerFilter(val label: String) {
    ALL("All"),
    INCOME("Income"),
    EXPENSE("Expense"),
    LOAN("Loan"),
    SAVINGS("Savings"),
    INVESTMENT("Investment"),
    FAMILY_CONTRIBUTION("Family Contribution")
}

enum class MemberLedgerDatePreset(val label: String) {
    ALL_TIME("All Dates"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    THIS_MONTH("This Month"),
    CUSTOM("Custom Range")
}

enum class MemberLedgerApprovalStatus(val label: String) {
    APPROVED("Approved"),
    PENDING_REVIEW("Pending Review"),
    VOIDED("Voided"),
    SETTLED("Settled")
}

data class MemberPrivacySettings(
    val showAvailableBalance: Boolean = true,
    val showIncomeAndExpense: Boolean = true,
    val showFamilyContributions: Boolean = true,
    val showLoans: Boolean = true,
    val showSavings: Boolean = true,
    val showInvestments: Boolean = true,
    val showTransactionHistory: Boolean = true,
    val allowAdminDetailedAudit: Boolean = true
) {
    companion object {
        fun fromEntity(card: LocalMemberCardEntity?): MemberPrivacySettings {
            if (card == null) return MemberPrivacySettings()
            return MemberPrivacySettings(
                showAvailableBalance = card.showAvailableBalance,
                showIncomeAndExpense = card.showIncomeAndExpense,
                showFamilyContributions = card.showFamilyContributions,
                showLoans = card.showLoans,
                showSavings = card.showSavings,
                showInvestments = card.showInvestments,
                showTransactionHistory = card.showTransactionHistory,
                allowAdminDetailedAudit = card.allowAdminDetailedAudit
            )
        }
    }
}

data class MemberFinancialSummary(
    val currencyCode: String = "BDT",
    val availableBalanceMinor: Long = 0L,
    val familyTotalBalanceMinor: Long = 0L,
    val totalIncomeMinor: Long = 0L,
    val totalExpenseMinor: Long = 0L,
    val familyContributionsMinor: Long = 0L,
    val outstandingLoansGivenMinor: Long = 0L,
    val outstandingLoansReceivedMinor: Long = 0L,
    val savingsBalanceMinor: Long = 0L,
    val investmentPrincipalMinor: Long = 0L,
    val investmentCurrentValueMinor: Long = 0L,
    val investmentProfitMinor: Long = 0L,
    val investmentLossMinor: Long = 0L,
    val investmentNetProfitLossMinor: Long = 0L,
    val confirmedTransactionCount: Int = 0
)

data class MemberLedgerEntry(
    val transactionId: String,
    val title: String,
    val type: MemberLedgerItemType,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtMillis: Long,
    val relatedMemberName: String,
    val approvalStatus: MemberLedgerApprovalStatus,
    val referenceId: String,
    val description: String,
    val syncStatus: String = "SYNCED",
    val sourceCollection: String = "transactions"
)

data class DigitalMemberCardData(
    val userId: String,
    val familyId: String,
    val cardMemberId: String,
    val familyName: String,
    val fullName: String,
    val photoUrl: String? = null,
    val role: FamilyRole,
    val membershipStatus: String,
    val relationLabel: String? = null,
    val joinedAtMillis: Long,
    val privacy: MemberPrivacySettings,
    val rawSummary: MemberFinancialSummary,
    val canViewerSeeAvailableBalance: Boolean
)

data class MemberProfileViewData(
    val card: DigitalMemberCardData,
    val isViewerOwner: Boolean,
    val isViewerAdmin: Boolean,
    val canViewerAuditDetails: Boolean,
    val canSeeBalance: Boolean,
    val canSeeIncomeExpense: Boolean,
    val canSeeContributions: Boolean,
    val canSeeLoans: Boolean,
    val canSeeSavings: Boolean,
    val canSeeInvestments: Boolean,
    val canSeeHistory: Boolean,
    val ledgerEntries: List<MemberLedgerEntry>
)

/**
 * Deterministic, decimal-safe financial accounting engine for individual Digital Member Cards
 * and Member Profiles (Prompt 14).
 *
 * Strict accounting separation rules enforced in minor integer units (no Double/Float math):
 * 1. Loan principal received (BORROWED) is NEVER counted as income.
 * 2. Loan principal repayment is NEVER counted as ordinary expense.
 * 3. Savings transfers (deposits/withdrawals) NEVER create new income.
 * 4. Investment principal is NEVER counted as profit; profit and loss are tracked separately.
 * 5. Family contributions are tracked separately from ordinary income.
 * 6. No money is counted twice across income, contributions, loans, savings, or investments.
 * 7. Private identity fields (phone number, masked phone, DOB, password) are NEVER exposed.
 */
object MemberAccountEngine {

    fun isContributionTransaction(tx: LocalTransactionEntity): Boolean {
        val type = tx.transactionType.trim().uppercase()
        val cat = tx.category.trim().lowercase()
        val method = tx.paymentMethod.orEmpty().trim().lowercase()
        val desc = tx.description.trim().lowercase()
        return when {
            cat == "personal contribution" || cat == "family contribution" -> true
            method == "personal contribution" || method == "family contribution" -> true
            type == TransactionType.TRANSFER.name &&
                (cat.contains("contribution") || method.contains("contribution") || desc.contains("contribution")) -> true
            else -> false
        }
    }

    fun buildDirectoryCards(
        family: LocalFamilyEntity,
        members: List<LocalFamilyMemberEntity>,
        memberCards: List<LocalMemberCardEntity>,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        viewerUid: String,
        viewerIsAdmin: Boolean,
        searchQuery: String = "",
        sortOption: MemberSortOption = MemberSortOption.ROLE_ADMIN_FIRST
    ): List<DigitalMemberCardData> {
        val cardByUid = memberCards.associateBy { it.userId }
        val familyTx = transactions.filter {
            it.familyId == family.familyId &&
                !it.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                !it.recordStatus.equals(RecordStatus.PENDING_REVIEW.name, ignoreCase = true)
        }
        val familyAllTimeIncome = MoneyCalculator.sumMinorUnits(
            familyTx.filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )
        val familyAllTimeExpense = MoneyCalculator.sumMinorUnits(
            familyTx.filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )
        val familyTotalBalanceMinor = familyAllTimeIncome - familyAllTimeExpense

        val cleanQuery = searchQuery.trim().lowercase()
        val cards = members.mapNotNull { member ->
            val cardEntity = cardByUid[member.userId]
            val resolvedCardId = cardEntity?.cardMemberId?.takeIf { FamilySecurityHelper.isValidMemberCardId16(it) }
                ?: member.cardMemberId.takeIf { FamilySecurityHelper.isValidMemberCardId16(it) }
                ?: deterministicFallbackCardId(family.familyId, member.userId)
            val resolvedStatus = when {
                family.isSuspended(member.userId) -> "SUSPENDED"
                member.memberStatus.equals("SUSPENDED", ignoreCase = true) -> "SUSPENDED"
                cardEntity?.memberStatus.equals("SUSPENDED", ignoreCase = true) -> "SUSPENDED"
                else -> "ACTIVE"
            }
            val resolvedRole = if (member.userId == family.ownerId || FamilyRole.fromRaw(member.role) == FamilyRole.ADMIN) {
                FamilyRole.ADMIN
            } else {
                FamilyRole.MEMBER
            }
            val privacy = MemberPrivacySettings.fromEntity(cardEntity)
            val summary = calculateMemberSummary(
                family = family,
                memberUserId = member.userId,
                familyTotalBalanceMinor = familyTotalBalanceMinor,
                transactions = transactions,
                loans = loans,
                savings = savings,
                investments = investments
            )
            val isOwner = viewerUid == member.userId
            val canSeeBalance = isOwner || privacy.showAvailableBalance || (viewerIsAdmin && privacy.allowAdminDetailedAudit)
            DigitalMemberCardData(
                userId = member.userId,
                familyId = family.familyId,
                cardMemberId = resolvedCardId,
                familyName = cardEntity?.familyName?.takeIf { it.isNotBlank() } ?: family.name,
                fullName = member.displayName.ifBlank { cardEntity?.displayName.orEmpty().ifBlank { "Family Member" } },
                photoUrl = cardEntity?.photoUrl,
                role = resolvedRole,
                membershipStatus = resolvedStatus,
                relationLabel = member.relationLabel,
                joinedAtMillis = cardEntity?.joinedAtMillis?.takeIf { it > 0L } ?: member.joinedAtMillis,
                privacy = privacy,
                rawSummary = summary,
                canViewerSeeAvailableBalance = canSeeBalance
            )
        }.filter { card ->
            if (cleanQuery.isEmpty()) true
            else card.fullName.lowercase().contains(cleanQuery) ||
                card.cardMemberId.lowercase().contains(cleanQuery) ||
                card.role.label.lowercase().contains(cleanQuery)
        }

        return when (sortOption) {
            MemberSortOption.ROLE_ADMIN_FIRST -> cards.sortedWith(
                compareBy<DigitalMemberCardData> { if (it.role == FamilyRole.ADMIN) 0 else 1 }
                    .thenBy { if (it.membershipStatus == "ACTIVE") 0 else 1 }
                    .thenBy { it.fullName.lowercase() }
            )
            MemberSortOption.NAME_ASC -> cards.sortedBy { it.fullName.lowercase() }
            MemberSortOption.BALANCE_DESC -> cards.sortedByDescending {
                if (it.canViewerSeeAvailableBalance) it.rawSummary.availableBalanceMinor else Long.MIN_VALUE
            }
            MemberSortOption.JOIN_DATE_DESC -> cards.sortedByDescending { it.joinedAtMillis }
            MemberSortOption.STATUS_ACTIVE_FIRST -> cards.sortedWith(
                compareBy<DigitalMemberCardData> { if (it.membershipStatus == "ACTIVE") 0 else 1 }
                    .thenBy { it.fullName.lowercase() }
            )
        }
    }

    fun calculateMemberSummary(
        family: LocalFamilyEntity,
        memberUserId: String,
        familyTotalBalanceMinor: Long,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>
    ): MemberFinancialSummary {
        val confirmedMemberTx = transactions.filter { tx ->
            tx.familyId == family.familyId &&
                tx.effectiveMemberId() == memberUserId &&
                !tx.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                !tx.recordStatus.equals(RecordStatus.PENDING_REVIEW.name, ignoreCase = true)
        }

        val contributionsTx = confirmedMemberTx.filter { isContributionTransaction(it) }
        val nonContributionTx = confirmedMemberTx.filterNot { isContributionTransaction(it) }

        val familyContributionsMinor = MoneyCalculator.sumMinorUnits(
            contributionsTx.map { it.effectiveAmountMinor() }
        )
        val totalIncomeMinor = MoneyCalculator.sumMinorUnits(
            nonContributionTx
                .filter { it.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )
        val totalExpenseMinor = MoneyCalculator.sumMinorUnits(
            nonContributionTx
                .filter { it.transactionType.equals(TransactionType.EXPENSE.name, ignoreCase = true) }
                .map { it.effectiveAmountMinor() }
        )

        val memberLoans = loans.filter { loan ->
            loan.familyId == family.familyId &&
                loan.createdByUserId == memberUserId &&
                !loan.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                !loan.recordStatus.equals(RecordStatus.PENDING_REVIEW.name, ignoreCase = true)
        }
        val outstandingLoansGivenMinor = MoneyCalculator.sumMinorUnits(
            memberLoans
                .filter { LoanDirection.fromRaw(it.loanDirection) == LoanDirection.LENT && !it.recordStatus.equals(RecordStatus.SETTLED.name, ignoreCase = true) }
                .map { it.effectiveRemainingMinor() }
        )
        val outstandingLoansReceivedMinor = MoneyCalculator.sumMinorUnits(
            memberLoans
                .filter { LoanDirection.fromRaw(it.loanDirection) == LoanDirection.BORROWED && !it.recordStatus.equals(RecordStatus.SETTLED.name, ignoreCase = true) }
                .map { it.effectiveRemainingMinor() }
        )

        val memberSavings = savings.filter { goal ->
            goal.familyId == family.familyId &&
                goal.createdByUserId == memberUserId &&
                !goal.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                !goal.recordStatus.equals(RecordStatus.PENDING_REVIEW.name, ignoreCase = true)
        }
        val savingsBalanceMinor = MoneyCalculator.sumMinorUnits(
            memberSavings.map { it.effectiveCurrentMinor() }
        )

        val memberInvestments = investments.filter { inv ->
            inv.familyId == family.familyId &&
                inv.createdByUserId == memberUserId &&
                !inv.recordStatus.equals(RecordStatus.VOIDED.name, ignoreCase = true) &&
                !inv.recordStatus.equals(RecordStatus.PENDING_REVIEW.name, ignoreCase = true)
        }
        val investmentPrincipalMinor = MoneyCalculator.sumMinorUnits(
            memberInvestments.map { it.effectivePrincipalMinor() }
        )
        val investmentCurrentValueMinor = MoneyCalculator.sumMinorUnits(
            memberInvestments.map { it.effectiveCurrentValueMinor() }
        )
        var totalGainMinor = 0L
        var totalLossMinor = 0L
        memberInvestments.forEach { inv ->
            val delta = inv.effectiveCurrentValueMinor() - inv.effectivePrincipalMinor()
            if (delta >= 0L) totalGainMinor += delta else totalLossMinor += (-delta)
        }
        val netProfitLossMinor = investmentCurrentValueMinor - investmentPrincipalMinor

        // Decimal-safe Available Balance without double-counting:
        // Cash in: ordinary income + family contributions + remaining net borrowed cash
        // Cash out / allocated: ordinary expenses + remaining lent cash + savings reserves + investment principal
        val availableBalanceMinor = totalIncomeMinor +
            familyContributionsMinor -
            totalExpenseMinor +
            outstandingLoansReceivedMinor -
            outstandingLoansGivenMinor -
            savingsBalanceMinor -
            investmentPrincipalMinor

        return MemberFinancialSummary(
            currencyCode = family.currencyCode.ifBlank { "BDT" },
            availableBalanceMinor = availableBalanceMinor,
            familyTotalBalanceMinor = familyTotalBalanceMinor,
            totalIncomeMinor = totalIncomeMinor,
            totalExpenseMinor = totalExpenseMinor,
            familyContributionsMinor = familyContributionsMinor,
            outstandingLoansGivenMinor = outstandingLoansGivenMinor,
            outstandingLoansReceivedMinor = outstandingLoansReceivedMinor,
            savingsBalanceMinor = savingsBalanceMinor,
            investmentPrincipalMinor = investmentPrincipalMinor,
            investmentCurrentValueMinor = investmentCurrentValueMinor,
            investmentProfitMinor = totalGainMinor,
            investmentLossMinor = totalLossMinor,
            investmentNetProfitLossMinor = netProfitLossMinor,
            confirmedTransactionCount = confirmedMemberTx.size
        )
    }

    fun buildMemberLedger(
        family: LocalFamilyEntity,
        memberUserId: String,
        members: List<LocalFamilyMemberEntity>,
        transactions: List<LocalTransactionEntity>,
        loans: List<LocalLoanEntity>,
        savings: List<LocalSavingsEntity>,
        investments: List<LocalInvestmentEntity>,
        financialEvents: List<LocalFinancialEventEntity>
    ): List<MemberLedgerEntry> {
        val memberNames = members.associate { it.userId to it.displayName.ifBlank { "Family Member" } }
        val ownerName = memberNames[memberUserId] ?: "Member"
        val entries = mutableListOf<MemberLedgerEntry>()

        transactions.filter { it.familyId == family.familyId && it.effectiveMemberId() == memberUserId }
            .forEach { tx ->
                val itemType = when {
                    isContributionTransaction(tx) -> MemberLedgerItemType.FAMILY_CONTRIBUTION
                    tx.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true) -> MemberLedgerItemType.INCOME
                    else -> MemberLedgerItemType.EXPENSE
                }
                val title = tx.description.takeIf { it.isNotBlank() }?.take(48) ?: tx.category
                entries.add(
                    MemberLedgerEntry(
                        transactionId = tx.recordId,
                        title = title,
                        type = itemType,
                        amountMinor = tx.effectiveAmountMinor(),
                        currencyCode = tx.currency,
                        occurredAtMillis = tx.occurredAtMillis,
                        relatedMemberName = ownerName,
                        approvalStatus = mapApprovalStatus(tx.recordStatus),
                        referenceId = tx.idempotencyKey.ifBlank { tx.recordId },
                        description = tx.description.ifBlank {
                            "${tx.category} (${tx.paymentMethod ?: itemType.label})"
                        },
                        syncStatus = tx.syncStatus,
                        sourceCollection = "transactions"
                    )
                )
            }

        val memberLoansById = loans.filter { it.familyId == family.familyId && it.createdByUserId == memberUserId }
            .associateBy { it.recordId }

        memberLoansById.values.forEach { loan ->
            val directionLabel = LoanDirection.fromRaw(loan.loanDirection).label
            val related = loan.counterparty.ifBlank {
                if (LoanDirection.fromRaw(loan.loanDirection) == LoanDirection.BORROWED) loan.lenderName else loan.borrowerName
            }.ifBlank { ownerName }
            entries.add(
                MemberLedgerEntry(
                    transactionId = loan.recordId,
                    title = "$directionLabel Loan • $related",
                    type = MemberLedgerItemType.LOAN,
                    amountMinor = loan.effectiveAmountMinor(),
                    currencyCode = loan.currency,
                    occurredAtMillis = loan.startDateMillis ?: loan.createdAtMillis,
                    relatedMemberName = related,
                    approvalStatus = mapApprovalStatus(loan.recordStatus),
                    referenceId = loan.idempotencyKey.ifBlank { loan.recordId },
                    description = loan.description.ifBlank {
                        "$directionLabel principal with $related (not counted as income/expense)"
                    },
                    syncStatus = loan.syncStatus,
                    sourceCollection = "loans"
                )
            )
        }

        val memberSavingsIds = savings.filter { it.familyId == family.familyId && it.createdByUserId == memberUserId }
            .associateBy { it.recordId }
        val memberSavingsWithEvents = mutableSetOf<String>()

        val memberInvestmentIds = investments.filter { it.familyId == family.familyId && it.createdByUserId == memberUserId }
            .associateBy { it.recordId }

        financialEvents.filter { it.familyId == family.familyId }.forEach { ev ->
            when (FinancialModuleType.fromRaw(ev.moduleType)) {
                FinancialModuleType.LOANS -> {
                    val parentLoan = memberLoansById[ev.parentRecordId]
                    if ((parentLoan != null || ev.actorUserId == memberUserId) && ev.eventType.equals("REPAYMENT", ignoreCase = true)) {
                        val counterparty = parentLoan?.counterparty?.ifBlank { ownerName } ?: ownerName
                        entries.add(
                            MemberLedgerEntry(
                                transactionId = ev.eventId,
                                title = "Loan Repayment • $counterparty",
                                type = MemberLedgerItemType.LOAN,
                                amountMinor = ev.amountMinor,
                                currencyCode = ev.currency,
                                occurredAtMillis = ev.eventDateMillis,
                                relatedMemberName = memberNames[ev.actorUserId] ?: counterparty,
                                approvalStatus = MemberLedgerApprovalStatus.APPROVED,
                                referenceId = ev.parentRecordId,
                                description = ev.note.ifBlank { "Loan principal repayment (isolated from expenses)" },
                                syncStatus = ev.syncStatus,
                                sourceCollection = "financialEvents"
                            )
                        )
                    }
                }
                FinancialModuleType.SAVINGS -> {
                    val parentGoal = memberSavingsIds[ev.parentRecordId]
                    if ((parentGoal != null || ev.actorUserId == memberUserId) && ev.amountMinor > 0L) {
                        memberSavingsWithEvents.add(ev.parentRecordId)
                        val goalName = parentGoal?.goalName ?: "Savings Goal"
                        val actionLabel = if (ev.eventType.equals("WITHDRAWAL", ignoreCase = true)) {
                            "Savings Withdrawal"
                        } else {
                            "Savings Deposit"
                        }
                        entries.add(
                            MemberLedgerEntry(
                                transactionId = ev.eventId,
                                title = "$actionLabel • $goalName",
                                type = MemberLedgerItemType.SAVINGS,
                                amountMinor = ev.amountMinor,
                                currencyCode = ev.currency,
                                occurredAtMillis = ev.eventDateMillis,
                                relatedMemberName = memberNames[ev.actorUserId] ?: ownerName,
                                approvalStatus = MemberLedgerApprovalStatus.APPROVED,
                                referenceId = ev.parentRecordId,
                                description = ev.note.ifBlank { "$actionLabel for $goalName (internal transfer)" },
                                syncStatus = ev.syncStatus,
                                sourceCollection = "financialEvents"
                            )
                        )
                    }
                }
                FinancialModuleType.INVESTMENTS -> {
                    val parentInv = memberInvestmentIds[ev.parentRecordId]
                    if ((parentInv != null || ev.actorUserId == memberUserId) && !ev.eventType.equals("INITIAL_INVESTMENT", ignoreCase = true)) {
                        val asset = parentInv?.assetName ?: "Investment"
                        entries.add(
                            MemberLedgerEntry(
                                transactionId = ev.eventId,
                                title = "Investment Valuation • $asset",
                                type = MemberLedgerItemType.INVESTMENT,
                                amountMinor = ev.amountMinor,
                                currencyCode = ev.currency,
                                occurredAtMillis = ev.eventDateMillis,
                                relatedMemberName = memberNames[ev.actorUserId] ?: ownerName,
                                approvalStatus = MemberLedgerApprovalStatus.APPROVED,
                                referenceId = ev.parentRecordId,
                                description = ev.note.ifBlank { "Portfolio valuation update (${ev.eventType})" },
                                syncStatus = ev.syncStatus,
                                sourceCollection = "financialEvents"
                            )
                        )
                    }
                }
                FinancialModuleType.BUDGETS -> Unit
            }
        }

        // Savings records with opening amount but no corresponding event yet
        memberSavingsIds.values.forEach { goal ->
            if (goal.recordId !in memberSavingsWithEvents && goal.effectiveCurrentMinor() > 0L) {
                entries.add(
                    MemberLedgerEntry(
                        transactionId = goal.recordId,
                        title = "Savings Goal • ${goal.goalName}",
                        type = MemberLedgerItemType.SAVINGS,
                        amountMinor = goal.effectiveCurrentMinor(),
                        currencyCode = goal.currency,
                        occurredAtMillis = goal.createdAtMillis,
                        relatedMemberName = ownerName,
                        approvalStatus = mapApprovalStatus(goal.recordStatus),
                        referenceId = goal.idempotencyKey.ifBlank { goal.recordId },
                        description = goal.description.ifBlank { "Savings reserve (not counted as new income)" },
                        syncStatus = goal.syncStatus,
                        sourceCollection = "savings"
                    )
                )
            }
        }

        memberInvestmentIds.values.forEach { inv ->
            entries.add(
                MemberLedgerEntry(
                    transactionId = inv.recordId,
                    title = "Investment Principal • ${inv.assetName}",
                    type = MemberLedgerItemType.INVESTMENT,
                    amountMinor = inv.effectivePrincipalMinor(),
                    currencyCode = inv.currency,
                    occurredAtMillis = inv.investedAtMillis,
                    relatedMemberName = ownerName,
                    approvalStatus = mapApprovalStatus(inv.recordStatus),
                    referenceId = inv.idempotencyKey.ifBlank { inv.recordId },
                    description = inv.description.ifBlank {
                        val delta = inv.effectiveCurrentValueMinor() - inv.effectivePrincipalMinor()
                        val pnlLabel = if (delta >= 0L) "Unrealized Profit" else "Unrealized Loss"
                        "${inv.assetType}: Principal kept separate from $pnlLabel"
                    },
                    syncStatus = inv.syncStatus,
                    sourceCollection = "investments"
                )
            )
        }

        return entries.sortedByDescending { it.occurredAtMillis }
    }

    fun filterLedgerEntries(
        entries: List<MemberLedgerEntry>,
        typeFilter: MemberLedgerFilter,
        datePreset: MemberLedgerDatePreset,
        customStartMillis: Long? = null,
        customEndMillis: Long? = null,
        searchQuery: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<MemberLedgerEntry> {
        val (rangeStart, rangeEnd) = dateRangeForPreset(datePreset, customStartMillis, customEndMillis, nowMillis, zoneId)
        val cleanQuery = searchQuery.trim().lowercase()
        return entries.filter { entry ->
            val typeOk = when (typeFilter) {
                MemberLedgerFilter.ALL -> true
                MemberLedgerFilter.INCOME -> entry.type == MemberLedgerItemType.INCOME
                MemberLedgerFilter.EXPENSE -> entry.type == MemberLedgerItemType.EXPENSE
                MemberLedgerFilter.LOAN -> entry.type == MemberLedgerItemType.LOAN
                MemberLedgerFilter.SAVINGS -> entry.type == MemberLedgerItemType.SAVINGS
                MemberLedgerFilter.INVESTMENT -> entry.type == MemberLedgerItemType.INVESTMENT
                MemberLedgerFilter.FAMILY_CONTRIBUTION -> entry.type == MemberLedgerItemType.FAMILY_CONTRIBUTION
            }
            val dateOk = (rangeStart == null || entry.occurredAtMillis >= rangeStart) &&
                (rangeEnd == null || entry.occurredAtMillis <= rangeEnd)
            val queryOk = cleanQuery.isEmpty() ||
                entry.title.lowercase().contains(cleanQuery) ||
                entry.description.lowercase().contains(cleanQuery) ||
                entry.referenceId.lowercase().contains(cleanQuery) ||
                entry.transactionId.lowercase().contains(cleanQuery) ||
                entry.relatedMemberName.lowercase().contains(cleanQuery)
            typeOk && dateOk && queryOk
        }
    }

    fun buildMemberProfileView(
        card: DigitalMemberCardData,
        viewerUid: String,
        viewerIsAdmin: Boolean,
        allLedgerEntries: List<MemberLedgerEntry>
    ): MemberProfileViewData {
        val isOwner = viewerUid == card.userId
        val p = card.privacy
        val adminOverrideAllowed = viewerIsAdmin && p.allowAdminDetailedAudit
        return MemberProfileViewData(
            card = card,
            isViewerOwner = isOwner,
            isViewerAdmin = viewerIsAdmin,
            canViewerAuditDetails = adminOverrideAllowed,
            canSeeBalance = isOwner || p.showAvailableBalance || adminOverrideAllowed,
            canSeeIncomeExpense = isOwner || p.showIncomeAndExpense || adminOverrideAllowed,
            canSeeContributions = isOwner || p.showFamilyContributions || adminOverrideAllowed,
            canSeeLoans = isOwner || p.showLoans || adminOverrideAllowed,
            canSeeSavings = isOwner || p.showSavings || adminOverrideAllowed,
            canSeeInvestments = isOwner || p.showInvestments || adminOverrideAllowed,
            canSeeHistory = isOwner || p.showTransactionHistory || adminOverrideAllowed,
            ledgerEntries = if (isOwner || p.showTransactionHistory || adminOverrideAllowed) allLedgerEntries else emptyList()
        )
    }

    private fun dateRangeForPreset(
        preset: MemberLedgerDatePreset,
        customStartMillis: Long?,
        customEndMillis: Long?,
        nowMillis: Long,
        zoneId: ZoneId
    ): Pair<Long?, Long?> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        return when (preset) {
            MemberLedgerDatePreset.ALL_TIME -> null to null
            MemberLedgerDatePreset.TODAY -> {
                val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            MemberLedgerDatePreset.LAST_7_DAYS -> {
                val start = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            MemberLedgerDatePreset.THIS_MONTH -> {
                val firstDay = LocalDate.of(today.year, today.month, 1)
                val start = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            MemberLedgerDatePreset.CUSTOM -> {
                val s = customStartMillis
                val e = customEndMillis?.let { endRaw ->
                    val endDate = Instant.ofEpochMilli(endRaw).atZone(zoneId).toLocalDate()
                    endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                }
                s to e
            }
        }
    }

    private fun mapApprovalStatus(raw: String): MemberLedgerApprovalStatus {
        return when (RecordStatus.fromRaw(raw)) {
            RecordStatus.PENDING_REVIEW -> MemberLedgerApprovalStatus.PENDING_REVIEW
            RecordStatus.VOIDED -> MemberLedgerApprovalStatus.VOIDED
            RecordStatus.SETTLED -> MemberLedgerApprovalStatus.SETTLED
            RecordStatus.ACTIVE, RecordStatus.ARCHIVED -> MemberLedgerApprovalStatus.APPROVED
        }
    }

    fun deterministicFallbackCardId(familyId: String, userId: String): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$familyId|$userId".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            val unsigned = digest[i].toInt() and 0xFF
            sb.append(alphabet[unsigned % alphabet.length])
        }
        return sb.toString()
    }
}
