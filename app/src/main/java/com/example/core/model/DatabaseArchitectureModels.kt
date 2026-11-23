package com.example.core.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

enum class RecordStatus(val label: String) {
    ACTIVE("Active"),
    ARCHIVED("Archived"),
    SETTLED("Settled"),
    VOIDED("Voided"),
    PENDING_REVIEW("Pending Review");

    companion object {
        fun fromRaw(raw: String?): RecordStatus =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ACTIVE
    }
}

enum class TransactionType(val label: String) {
    INCOME("Income"),
    EXPENSE("Expense"),
    TRANSFER("Transfer");

    companion object {
        fun fromRaw(raw: String?): TransactionType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: EXPENSE
    }
}

enum class LoanDirection(val label: String) {
    BORROWED("Borrowed"),
    LENT("Lent");

    companion object {
        fun fromRaw(raw: String?): LoanDirection =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: BORROWED
    }
}

enum class InvestmentAssetType(val label: String) {
    STOCK("Stock"),
    ETF("ETF"),
    MUTUAL_FUND("Mutual Fund"),
    BOND("Bond"),
    REAL_ESTATE("Real Estate"),
    CRYPTO("Crypto"),
    OTHER("Other");

    companion object {
        fun fromRaw(raw: String?): InvestmentAssetType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: OTHER
    }
}

enum class BudgetScope(val label: String) {
    MONTHLY_OVERALL("Monthly Overall"),
    CATEGORY("Category");

    companion object {
        fun fromRaw(raw: String?): BudgetScope =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: CATEGORY
    }
}

enum class FinancialModuleType(val label: String) {
    LOANS("Loans"),
    SAVINGS("Savings"),
    INVESTMENTS("Investments"),
    BUDGETS("Budgets");

    companion object {
        fun fromRaw(raw: String?): FinancialModuleType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: LOANS
    }
}

enum class ChatMessageType {
    TEXT,
    SYSTEM,
    RECORD_REF;

    companion object {
        fun fromRaw(raw: String?): ChatMessageType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: TEXT
    }
}

enum class ChatRetentionOption(val days: Int, val label: String) {
    DAYS_30(30, "30 Days"),
    DAYS_90(90, "90 Days"),
    YEAR_1(365, "1 Year"),
    FOREVER(0, "Keep Forever");

    companion object {
        fun fromDays(days: Int): ChatRetentionOption =
            entries.firstOrNull { it.days == days } ?: if (days <= 0) FOREVER else DAYS_90
    }
}

enum class NotificationType(val label: String, val defaultModuleName: String) {
    NEW_MESSAGE("Messages", "FAMILY_CHAT"),
    NEW_TRANSACTION("Transactions", "EXPENSE"),
    FAMILY_INVITATION("Invitations", "FAMILY_MANAGEMENT"),
    INVITATION_APPROVAL("Approvals", "FAMILY_MANAGEMENT"),
    LOAN_DUE_DATE("Loan Due Dates", "LOANS"),
    BUDGET_ALERT("Budget Alerts", "BUDGETS"),
    SAVINGS_MILESTONE("Savings Milestones", "SAVINGS"),
    CALL_INCOMING("Incoming Calls", "FAMILY_MEET"),
    CALL_MISSED("Missed Calls", "FAMILY_MEET"),
    MEETING_INVITE("Meeting Invites", "FAMILY_MEET"),
    MEETING_STARTED("Meetings", "FAMILY_MEET"),
    PROPOSAL_SUBMITTED("Proposal Submitted", "FAMILY_DECISIONS"),
    PROPOSAL_VOTE_CAST("Proposal Vote Cast", "FAMILY_DECISIONS"),
    PROPOSAL_APPROVED("Proposal Approved", "FAMILY_DECISIONS"),
    PROPOSAL_REJECTED("Proposal Rejected", "FAMILY_DECISIONS"),
    PROPOSAL_NEAR_DEADLINE("Deadline Reminder", "FAMILY_DECISIONS"),
    PROPOSAL_EXPIRED("Proposal Expired", "FAMILY_DECISIONS"),
    PROPOSAL_EXECUTED("Proposal Executed", "FAMILY_DECISIONS"),
    GENERAL("General", "NOTIFICATIONS");

    companion object {
        fun fromRaw(raw: String?): NotificationType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: GENERAL
    }
}

enum class CallMediaType(val label: String) {
    AUDIO("Audio Call"),
    VIDEO("Video Call");

    companion object {
        fun fromRaw(raw: String?): CallMediaType =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: AUDIO
    }
}

enum class CallStatus(val label: String) {
    RINGING("Ringing"),
    CONNECTING("Connecting"),
    ACTIVE("In Call"),
    ENDED("Ended"),
    REJECTED("Declined"),
    MISSED("Missed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean
        get() = this == ENDED || this == REJECTED || this == MISSED || this == CANCELLED

    companion object {
        fun fromRaw(raw: String?): CallStatus =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ENDED
    }
}

enum class CallConnectionQuality(val label: String) {
    UNKNOWN("Measuring"),
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor");

    companion object {
        fun fromRaw(raw: String?): CallConnectionQuality =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNKNOWN
    }
}

enum class AudioOutputRoute(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    BLUETOOTH("Bluetooth"),
    WIRED_HEADSET("Headset")
}

enum class MeetingStatus(val label: String) {
    SCHEDULED("Scheduled"),
    LIVE("Live"),
    ENDED("Ended"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean
        get() = this == ENDED || this == CANCELLED

    companion object {
        fun fromRaw(raw: String?): MeetingStatus =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ENDED
    }
}

enum class MeetingParticipantState {
    INVITED,
    JOINED,
    LEFT,
    REMOVED;

    companion object {
        fun fromRaw(raw: String?): MeetingParticipantState =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: INVITED
    }
}

enum class AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    ARCHIVE,
    STATUS_CHANGE,
    MEMBER_CHANGE,
    SYNC;

    companion object {
        fun fromRaw(raw: String?): AuditAction =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UPDATE
    }
}

enum class IncomeCategory(val displayName: String) {
    SALARY("Salary"),
    BUSINESS("Business"),
    FREELANCING("Freelancing"),
    GIFTS("Gifts"),
    RENTAL_INCOME("Rental Income"),
    OTHER_INCOME("Other Income");

    companion object {
        val allNames: List<String> = entries.map { it.displayName }
    }
}

enum class ExpenseCategory(val displayName: String) {
    FOOD("Food"),
    SHOPPING("Shopping"),
    EDUCATION("Education"),
    MEDICAL("Medical"),
    TRANSPORTATION("Transportation"),
    HOUSING("Housing"),
    BILLS("Bills"),
    ENTERTAINMENT("Entertainment"),
    OTHER_EXPENSES("Other Expenses");

    companion object {
        val allNames: List<String> = entries.map { it.displayName }
    }
}

enum class RecurrenceFrequency(val label: String) {
    NONE("One-time"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    BIWEEKLY("Bi-weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly");

    companion object {
        fun fromRaw(raw: String?): RecurrenceFrequency =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: NONE
    }
}

enum class DateFilterPreset(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    LAST_MONTH("Last Month"),
    CUSTOM("Custom Range")
}

object RecurrenceScheduleHelper {
    fun computeNextOccurrenceMillis(
        baseMillis: Long,
        frequency: RecurrenceFrequency,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (frequency == RecurrenceFrequency.NONE) return null
        val zoned = Instant.ofEpochMilli(baseMillis).atZone(zoneId)
        val nextZoned = when (frequency) {
            RecurrenceFrequency.NONE -> return null
            RecurrenceFrequency.DAILY -> zoned.plusDays(1)
            RecurrenceFrequency.WEEKLY -> zoned.plusWeeks(1)
            RecurrenceFrequency.BIWEEKLY -> zoned.plusWeeks(2)
            RecurrenceFrequency.MONTHLY -> zoned.plusMonths(1)
            RecurrenceFrequency.YEARLY -> zoned.plusYears(1)
        }
        return nextZoned.toInstant().toEpochMilli()
    }

    fun startOfTodayMillis(nowMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

    fun endOfDayMillis(dayMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(dayMillis).atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L

    fun dateRangeForPreset(
        preset: DateFilterPreset,
        nowMillis: Long = System.currentTimeMillis(),
        customStartMillis: Long? = null,
        customEndMillis: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long?, Long?> {
        val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        return when (preset) {
            DateFilterPreset.ALL -> null to null
            DateFilterPreset.TODAY -> {
                val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            DateFilterPreset.THIS_WEEK -> {
                val startOfWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
                val start = startOfWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = startOfWeek.plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            DateFilterPreset.THIS_MONTH -> {
                val first = today.withDayOfMonth(1)
                val start = first.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = first.plusMonths(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            DateFilterPreset.LAST_MONTH -> {
                val firstThisMonth = today.withDayOfMonth(1)
                val firstLastMonth = firstThisMonth.minusMonths(1)
                val start = firstLastMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = firstThisMonth.atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                start to end
            }
            DateFilterPreset.CUSTOM -> {
                val start = customStartMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
                val end = customEndMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                }
                start to end
            }
        }
    }
}

enum class SyncState(val label: String) {
    SYNCED("Synced"),
    PENDING_CREATE("Pending Create"),
    PENDING_UPDATE("Pending Update"),
    CONFLICT("Conflict"),
    ERROR("Sync Error");

    companion object {
        fun fromRaw(raw: String?): SyncState =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: PENDING_CREATE
    }
}

object DatabaseIdentityHelper {
    private val secureRandom = SecureRandom()

    fun generateRecordId(prefix: String = "rec"): String {
        val bytes = ByteArray(10)
        secureRandom.nextBytes(bytes)
        val cleanPrefix = prefix.trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "rec" }
        return "${cleanPrefix}_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateAuditId(): String = generateRecordId("aud")

    fun generateEventId(): String = generateRecordId("evt")

    fun generateNotificationId(): String = generateRecordId("ntf")

    fun generateOutboxId(): String = generateRecordId("obx")

    fun generateOperationId(): String = generateRecordId("op")

    fun generateConflictId(): String = generateRecordId("cnf")

    fun generateReportId(familyId: String, periodKey: String): String {
        val cleanFam = familyId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "").take(24)
        val cleanPeriod = periodKey.trim().replace(Regex("[^a-zA-Z0-9_-]"), "").take(16)
        return "rpt_${cleanFam}_$cleanPeriod"
    }

    /**
     * Generates a deterministic SHA-256 idempotency key to prevent duplicate financial entries
     * across double-taps, offline retries, or background sync replays.
     */
    fun computeIdempotencyKey(
        familyId: String,
        collectionName: String,
        discriminator: String,
        amount: Double,
        currency: String,
        timeBucketMillis: Long
    ): String {
        val minor = MoneyCalculator.doubleToMinorUnits(amount, currency)
        return computeIdempotencyKeyMinor(
            familyId = familyId,
            collectionName = collectionName,
            discriminator = discriminator,
            amountMinor = minor,
            currency = currency,
            timeBucketMillis = timeBucketMillis
        )
    }

    fun computeIdempotencyKeyMinor(
        familyId: String,
        collectionName: String,
        discriminator: String,
        amountMinor: Long,
        currency: String,
        timeBucketMillis: Long
    ): String {
        val canonical = listOf(
            familyId.trim(),
            collectionName.trim().lowercase(),
            discriminator.trim().lowercase(),
            amountMinor.toString(),
            currency.trim().uppercase(),
            timeBucketMillis.toString()
        ).joinToString("|")
        return sha256Hex(canonical)
    }

    fun computeRecurringOccurrenceKey(
        familyId: String,
        parentRecordId: String,
        occurrenceDateMillis: Long
    ): String = sha256Hex("rec|${familyId.trim()}|${parentRecordId.trim()}|$occurrenceDateMillis")

    fun generateDeterministicRecurringRecordId(idempotencyKey: String): String {
        val clean = idempotencyKey.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
        return "rec_r_$clean"
    }

    fun computePayloadHash(raw: String): String = sha256Hex(raw)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class DatabaseHealthSnapshot(
    val familyId: String = "",
    val localEngineName: String = "Room SQLite v9 (IndexedDB-Parity Store)",
    val encryptionEngineName: String = "AES-256-GCM (AndroidKeyStore)",
    val transactionsCount: Int = 0,
    val loansCount: Int = 0,
    val savingsCount: Int = 0,
    val investmentsCount: Int = 0,
    val budgetsCount: Int = 0,
    val financialEventsCount: Int = 0,
    val chatsCount: Int = 0,
    val callsCount: Int = 0,
    val meetingsCount: Int = 0,
    val notificationsCount: Int = 0,
    val auditLogsCount: Int = 0,
    val cachedReportsCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val failedSyncCount: Int = 0,
    val unresolvedConflictsCount: Int = 0
) {
    val totalFinancialRecords: Int
        get() = transactionsCount + loansCount + savingsCount + investmentsCount + budgetsCount
}

@Keep
@IgnoreExtraProperties
data class TransactionDoc(
    @DocumentId val id: String = "",
    val recordId: String = "",
    val transactionId: String = "",
    val familyId: String = "",
    val createdByUserId: String = "",
    val memberId: String = "",
    val amount: Double = 0.0,
    val amountMinor: Long = 0L,
    val currency: String = "USD",
    val category: String = "",
    val description: String = "",
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val transactionType: String = TransactionType.EXPENSE.name,
    val paymentMethod: String? = null,
    val occurredAt: Timestamp? = null,
    val recurrenceFrequency: String = RecurrenceFrequency.NONE.name,
    val nextRecurrenceAt: Timestamp? = null,
    val recurringParentId: String? = null,
    val isRecurringTemplate: Boolean = false,
    val idempotencyKey: String = "",
    val syncVersion: Long = 1L,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(occurredAtMillis: Long, nextRecurrenceAtMillis: Long? = null): Map<String, Any> {
        val safeCreatedAt = minOf(occurredAtMillis, System.currentTimeMillis())
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, safeCurrency)
        val safeMajor = if (resolvedMinor > 0L) {
            MoneyCalculator.minorUnitsToDouble(resolvedMinor, safeCurrency)
        } else {
            amount.coerceIn(0.0, 1_000_000_000.0)
        }
        val resolvedMemberId = memberId.trim().ifBlank { createdByUserId }
        val resolvedTxId = transactionId.trim().ifBlank { recordId }
        return mapOf(
            "recordId" to recordId,
            "transactionId" to resolvedTxId,
            "familyId" to familyId,
            "createdByUserId" to createdByUserId,
            "memberId" to resolvedMemberId,
            "amount" to safeMajor,
            "amountMinor" to resolvedMinor,
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "General" },
            "description" to description.trim().take(300),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "transactionType" to TransactionType.fromRaw(transactionType).name,
            "paymentMethod" to paymentMethod?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "occurredAt" to Timestamp(Date(safeCreatedAt)),
            "recurrenceFrequency" to RecurrenceFrequency.fromRaw(recurrenceFrequency).name,
            "nextRecurrenceAt" to nextRecurrenceAtMillis?.let { Timestamp(Date(it)) },
            "recurringParentId" to recurringParentId?.trim()?.takeIf { it.isNotEmpty() },
            "isRecurringTemplate" to isRecurringTemplate,
            "idempotencyKey" to idempotencyKey,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    fun toUpdateMap(occurredAtMillis: Long, nextRecurrenceAtMillis: Long?): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, safeCurrency)
        val safeMajor = MoneyCalculator.minorUnitsToDouble(resolvedMinor, safeCurrency)
        val safeOccurred = minOf(occurredAtMillis, System.currentTimeMillis())
        return mapOf(
            "memberId" to memberId.trim().ifBlank { createdByUserId },
            "amount" to safeMajor,
            "amountMinor" to resolvedMinor,
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "General" },
            "description" to description.trim().take(300),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "transactionType" to TransactionType.fromRaw(transactionType).name,
            "paymentMethod" to (paymentMethod?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "occurredAt" to Timestamp(Date(safeOccurred)),
            "recurrenceFrequency" to RecurrenceFrequency.fromRaw(recurrenceFrequency).name,
            "nextRecurrenceAt" to (nextRecurrenceAtMillis?.let { Timestamp(Date(it)) } ?: FieldValue.delete()),
            "isRecurringTemplate" to isRecurringTemplate,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to (deviceId?.take(128)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class LoanDoc(
    @DocumentId val id: String = "",
    val recordId: String = "",
    val familyId: String = "",
    val createdByUserId: String = "",
    val amount: Double = 0.0,
    val amountMinor: Long = 0L,
    val remainingBalance: Double = 0.0,
    val remainingBalanceMinor: Long = 0L,
    val interestRateApr: Double? = null,
    val currency: String = "USD",
    val category: String = "",
    val description: String = "",
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val loanDirection: String = LoanDirection.BORROWED.name,
    val counterparty: String = "",
    val borrowerName: String = "",
    val borrowerContact: String = "",
    val lenderName: String = "",
    val lenderContact: String = "",
    val totalInstallments: Long = 1L,
    val paidInstallments: Long = 0L,
    val installmentAmountMinor: Long = 0L,
    val startDate: Timestamp? = null,
    val dueDate: Timestamp? = null,
    val idempotencyKey: String = "",
    val syncVersion: Long = 1L,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(dueDateMillis: Long?, startDateMillis: Long? = null): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedPrincipalMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, safeCurrency)
        val resolvedRemainingMinor = (if (remainingBalanceMinor > 0L || remainingBalance == 0.0) {
            remainingBalanceMinor
        } else {
            MoneyCalculator.doubleToMinorUnits(remainingBalance, safeCurrency)
        }).coerceIn(0L, resolvedPrincipalMinor)
        val safePrincipal = MoneyCalculator.minorUnitsToDouble(resolvedPrincipalMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0)
        val safeRemaining = MoneyCalculator.minorUnitsToDouble(resolvedRemainingMinor, safeCurrency).coerceIn(0.0, safePrincipal)
        val safeTotalInst = totalInstallments.coerceIn(1L, 600L)
        val safePaidInst = paidInstallments.coerceIn(0L, 600L)
        val resolvedInstallmentMinor = installmentAmountMinor.coerceIn(0L, 100_000_000_000L)
        return mapOf(
            "recordId" to recordId,
            "familyId" to familyId,
            "createdByUserId" to createdByUserId,
            "amount" to safePrincipal,
            "amountMinor" to resolvedPrincipalMinor,
            "remainingBalance" to safeRemaining,
            "remainingBalanceMinor" to resolvedRemainingMinor,
            "interestRateApr" to interestRateApr?.coerceIn(0.0, 200.0),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Loan" },
            "description" to description.trim().take(300),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "loanDirection" to LoanDirection.fromRaw(loanDirection).name,
            "counterparty" to counterparty.trim().take(80).ifBlank { "Counterparty" },
            "borrowerName" to borrowerName.trim().take(80),
            "borrowerContact" to borrowerContact.trim().take(120),
            "lenderName" to lenderName.trim().take(80),
            "lenderContact" to lenderContact.trim().take(120),
            "totalInstallments" to safeTotalInst,
            "paidInstallments" to safePaidInst,
            "installmentAmountMinor" to resolvedInstallmentMinor,
            "startDate" to startDateMillis?.let { Timestamp(Date(it)) },
            "dueDate" to dueDateMillis?.let { Timestamp(Date(it)) },
            "idempotencyKey" to idempotencyKey,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    fun toUpdateMap(dueDateMillis: Long?, startDateMillis: Long?): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedPrincipalMinor = if (amountMinor > 0L) amountMinor else MoneyCalculator.doubleToMinorUnits(amount, safeCurrency)
        val resolvedRemainingMinor = remainingBalanceMinor.coerceIn(0L, resolvedPrincipalMinor)
        val safePrincipal = MoneyCalculator.minorUnitsToDouble(resolvedPrincipalMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0)
        val safeRemaining = MoneyCalculator.minorUnitsToDouble(resolvedRemainingMinor, safeCurrency).coerceIn(0.0, safePrincipal)
        return mapOf(
            "amount" to safePrincipal,
            "amountMinor" to resolvedPrincipalMinor,
            "remainingBalance" to safeRemaining,
            "remainingBalanceMinor" to resolvedRemainingMinor,
            "interestRateApr" to (interestRateApr?.coerceIn(0.0, 200.0) ?: FieldValue.delete()),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Loan" },
            "description" to description.trim().take(300),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "loanDirection" to LoanDirection.fromRaw(loanDirection).name,
            "counterparty" to counterparty.trim().take(80).ifBlank { "Counterparty" },
            "borrowerName" to borrowerName.trim().take(80),
            "borrowerContact" to borrowerContact.trim().take(120),
            "lenderName" to lenderName.trim().take(80),
            "lenderContact" to lenderContact.trim().take(120),
            "totalInstallments" to totalInstallments.coerceIn(1L, 600L),
            "paidInstallments" to paidInstallments.coerceIn(0L, 600L),
            "installmentAmountMinor" to installmentAmountMinor.coerceIn(0L, 100_000_000_000L),
            "startDate" to (startDateMillis?.let { Timestamp(Date(it)) } ?: FieldValue.delete()),
            "dueDate" to (dueDateMillis?.let { Timestamp(Date(it)) } ?: FieldValue.delete()),
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to (deviceId?.take(128)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class SavingsDoc(
    @DocumentId val id: String = "",
    val recordId: String = "",
    val familyId: String = "",
    val createdByUserId: String = "",
    val amount: Double = 0.0,
    val amountMinor: Long = 0L,
    val targetAmount: Double = 1.0,
    val targetAmountMinor: Long = 100L,
    val currency: String = "USD",
    val category: String = "",
    val description: String = "",
    val goalName: String = "",
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val targetDate: Timestamp? = null,
    val idempotencyKey: String = "",
    val syncVersion: Long = 1L,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(targetDateMillis: Long?): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedCurrentMinor = amountMinor.coerceIn(0L, 100_000_000_000L)
        val resolvedTargetMinor = targetAmountMinor.coerceIn(1L, 100_000_000_000L)
        return mapOf(
            "recordId" to recordId,
            "familyId" to familyId,
            "createdByUserId" to createdByUserId,
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedCurrentMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "amountMinor" to resolvedCurrentMinor,
            "targetAmount" to MoneyCalculator.minorUnitsToDouble(resolvedTargetMinor, safeCurrency).coerceIn(0.01, 1_000_000_000.0),
            "targetAmountMinor" to resolvedTargetMinor,
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Savings" },
            "description" to description.trim().take(300),
            "goalName" to goalName.trim().take(80).ifBlank { "Savings Goal" },
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "targetDate" to targetDateMillis?.let { Timestamp(Date(it)) },
            "idempotencyKey" to idempotencyKey,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    fun toUpdateMap(targetDateMillis: Long?): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedCurrentMinor = amountMinor.coerceIn(0L, 100_000_000_000L)
        val resolvedTargetMinor = targetAmountMinor.coerceIn(1L, 100_000_000_000L)
        return mapOf(
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedCurrentMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "amountMinor" to resolvedCurrentMinor,
            "targetAmount" to MoneyCalculator.minorUnitsToDouble(resolvedTargetMinor, safeCurrency).coerceIn(0.01, 1_000_000_000.0),
            "targetAmountMinor" to resolvedTargetMinor,
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Savings" },
            "description" to description.trim().take(300),
            "goalName" to goalName.trim().take(80).ifBlank { "Savings Goal" },
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "targetDate" to (targetDateMillis?.let { Timestamp(Date(it)) } ?: FieldValue.delete()),
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to (deviceId?.take(128)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class InvestmentDoc(
    @DocumentId val id: String = "",
    val recordId: String = "",
    val familyId: String = "",
    val createdByUserId: String = "",
    val amount: Double = 0.0,
    val amountMinor: Long = 0L,
    val currentValue: Double = 0.0,
    val currentValueMinor: Long = 0L,
    val units: Double? = null,
    val currency: String = "USD",
    val category: String = "",
    val description: String = "",
    val assetName: String = "",
    val assetType: String = InvestmentAssetType.ETF.name,
    val symbol: String? = null,
    val investedAt: Timestamp? = null,
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val idempotencyKey: String = "",
    val syncVersion: Long = 1L,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(investedAtMillis: Long = System.currentTimeMillis()): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedPrincipalMinor = amountMinor.coerceIn(0L, 100_000_000_000L)
        val resolvedValueMinor = currentValueMinor.coerceIn(0L, 100_000_000_000L)
        val safeInvestedAt = minOf(investedAtMillis, System.currentTimeMillis())
        return mapOf(
            "recordId" to recordId,
            "familyId" to familyId,
            "createdByUserId" to createdByUserId,
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedPrincipalMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "amountMinor" to resolvedPrincipalMinor,
            "currentValue" to MoneyCalculator.minorUnitsToDouble(resolvedValueMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "currentValueMinor" to resolvedValueMinor,
            "units" to units?.coerceAtLeast(0.0),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Investment" },
            "description" to description.trim().take(300),
            "assetName" to assetName.trim().take(80).ifBlank { "Asset" },
            "assetType" to InvestmentAssetType.fromRaw(assetType).name,
            "symbol" to symbol?.trim()?.uppercase()?.take(20)?.takeIf { it.isNotEmpty() },
            "investedAt" to Timestamp(Date(safeInvestedAt)),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "idempotencyKey" to idempotencyKey,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    fun toUpdateMap(investedAtMillis: Long): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedPrincipalMinor = amountMinor.coerceIn(0L, 100_000_000_000L)
        val resolvedValueMinor = currentValueMinor.coerceIn(0L, 100_000_000_000L)
        val safeInvestedAt = minOf(investedAtMillis, System.currentTimeMillis())
        return mapOf(
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedPrincipalMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "amountMinor" to resolvedPrincipalMinor,
            "currentValue" to MoneyCalculator.minorUnitsToDouble(resolvedValueMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "currentValueMinor" to resolvedValueMinor,
            "units" to (units?.coerceAtLeast(0.0) ?: FieldValue.delete()),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "Investment" },
            "description" to description.trim().take(300),
            "assetName" to assetName.trim().take(80).ifBlank { "Asset" },
            "assetType" to InvestmentAssetType.fromRaw(assetType).name,
            "symbol" to (symbol?.trim()?.uppercase()?.take(20)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "investedAt" to Timestamp(Date(safeInvestedAt)),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to (deviceId?.take(128)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class BudgetDoc(
    @DocumentId val id: String = "",
    val recordId: String = "",
    val familyId: String = "",
    val createdByUserId: String = "",
    val amount: Double = 0.0,
    val amountMinor: Long = 0L,
    val spentAmount: Double = 0.0,
    val spentAmountMinor: Long = 0L,
    val alertThresholdPercent: Long = 80L,
    val currency: String = "USD",
    val category: String = "",
    val description: String = "",
    val periodKey: String = "2026-11",
    val budgetScope: String = BudgetScope.CATEGORY.name,
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val idempotencyKey: String = "",
    val syncVersion: Long = 1L,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedLimitMinor = amountMinor.coerceIn(1L, 100_000_000_000L)
        val resolvedSpentMinor = spentAmountMinor.coerceIn(0L, 100_000_000_000L)
        return mapOf(
            "recordId" to recordId,
            "familyId" to familyId,
            "createdByUserId" to createdByUserId,
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedLimitMinor, safeCurrency).coerceIn(0.01, 1_000_000_000.0),
            "amountMinor" to resolvedLimitMinor,
            "spentAmount" to MoneyCalculator.minorUnitsToDouble(resolvedSpentMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "spentAmountMinor" to resolvedSpentMinor,
            "alertThresholdPercent" to alertThresholdPercent.coerceIn(10L, 100L),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "General" },
            "description" to description.trim().take(300),
            "periodKey" to periodKey.trim(),
            "budgetScope" to BudgetScope.fromRaw(budgetScope).name,
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "idempotencyKey" to idempotencyKey,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    fun toUpdateMap(): Map<String, Any> {
        val safeCurrency = currency.trim().uppercase().take(6).ifBlank { "USD" }
        val resolvedLimitMinor = amountMinor.coerceIn(1L, 100_000_000_000L)
        val resolvedSpentMinor = spentAmountMinor.coerceIn(0L, 100_000_000_000L)
        return mapOf(
            "amount" to MoneyCalculator.minorUnitsToDouble(resolvedLimitMinor, safeCurrency).coerceIn(0.01, 1_000_000_000.0),
            "amountMinor" to resolvedLimitMinor,
            "spentAmount" to MoneyCalculator.minorUnitsToDouble(resolvedSpentMinor, safeCurrency).coerceIn(0.0, 1_000_000_000.0),
            "spentAmountMinor" to resolvedSpentMinor,
            "alertThresholdPercent" to alertThresholdPercent.coerceIn(10L, 100L),
            "currency" to safeCurrency,
            "category" to category.trim().take(60).ifBlank { "General" },
            "description" to description.trim().take(300),
            "periodKey" to periodKey.trim(),
            "budgetScope" to BudgetScope.fromRaw(budgetScope).name,
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "deviceId" to (deviceId?.take(128)?.takeIf { it.isNotEmpty() } ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class FinancialEventDoc(
    @DocumentId val id: String = "",
    val eventId: String = "",
    val familyId: String = "",
    val parentRecordId: String = "",
    val moduleType: String = FinancialModuleType.LOANS.name,
    val eventType: String = "CREATE",
    val actorUserId: String = "",
    val amountMinor: Long = 0L,
    val secondaryAmountMinor: Long? = null,
    val balanceAfterMinor: Long? = null,
    val currency: String = "USD",
    val note: String = "",
    val eventDate: Timestamp? = null,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(eventDateMillis: Long): Map<String, Any> {
        val safeDate = minOf(eventDateMillis, System.currentTimeMillis())
        return mapOf(
            "eventId" to eventId,
            "familyId" to familyId,
            "parentRecordId" to parentRecordId,
            "moduleType" to FinancialModuleType.fromRaw(moduleType).name,
            "eventType" to eventType.trim().uppercase().take(40).ifBlank { "EVENT" },
            "actorUserId" to actorUserId,
            "amountMinor" to amountMinor.coerceIn(0L, 100_000_000_000L),
            "secondaryAmountMinor" to secondaryAmountMinor?.coerceIn(0L, 100_000_000_000L),
            "balanceAfterMinor" to balanceAfterMinor?.coerceIn(0L, 100_000_000_000L),
            "currency" to currency.trim().uppercase().take(6).ifBlank { "USD" },
            "note" to note.trim().take(300),
            "eventDate" to Timestamp(Date(safeDate)),
            "memberUids" to memberUids.distinct().take(20),
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class ChatMessageDoc(
    @DocumentId val id: String = "",
    val messageId: String = "",
    val familyId: String = "",
    val senderUserId: String = "",
    val senderDisplayName: String = "",
    val senderPhotoUrl: String? = null,
    val senderRoleLabel: String? = null,
    val messageText: String = "",
    val messageType: String = ChatMessageType.TEXT.name,
    val relatedRecordId: String? = null,
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val readByUids: List<String> = emptyList(),
    val editedAt: Timestamp? = null,
    val deletedAt: Timestamp? = null,
    val deletedByUserId: String? = null,
    val expiresAt: Timestamp? = null,
    val syncVersion: Long = 1L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(expiresAtMillis: Long? = null): Map<String, Any> =
        mapOf(
            "messageId" to messageId,
            "familyId" to familyId,
            "senderUserId" to senderUserId,
            "senderDisplayName" to senderDisplayName.trim().take(80).ifBlank { "Member" },
            "senderPhotoUrl" to senderPhotoUrl?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
            "senderRoleLabel" to senderRoleLabel?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "messageText" to messageText.trim().take(1000),
            "messageType" to ChatMessageType.fromRaw(messageType).name,
            "relatedRecordId" to relatedRecordId?.takeIf { it.isNotBlank() },
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "readByUids" to readByUids.ifEmpty { listOf(senderUserId) }.distinct().take(20),
            "expiresAt" to expiresAtMillis?.let { Timestamp(Date(it)) },
            "syncVersion" to syncVersion.coerceAtLeast(1L),
            "memberUids" to memberUids.distinct().take(20),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FamilyNotificationDoc(
    @DocumentId val id: String = "",
    val notificationId: String = "",
    val familyId: String = "",
    val recipientUserId: String = "",
    val createdByUserId: String = "",
    val title: String = "",
    val body: String = "",
    val notificationType: String = "GENERAL",
    val relatedRecordId: String? = null,
    val deepLinkModule: String? = null,
    val acknowledged: Boolean = false,
    val readByUids: List<String> = emptyList(),
    val recordStatus: String = RecordStatus.ACTIVE.name,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "notificationId" to notificationId,
            "familyId" to familyId,
            "recipientUserId" to recipientUserId,
            "createdByUserId" to createdByUserId,
            "title" to title.trim().take(100),
            "body" to body.trim().take(400),
            "notificationType" to notificationType.trim().take(40).ifBlank { "GENERAL" },
            "relatedRecordId" to relatedRecordId?.takeIf { it.isNotBlank() },
            "deepLinkModule" to deepLinkModule?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "acknowledged" to acknowledged,
            "readByUids" to readByUids.distinct().take(20),
            "recordStatus" to RecordStatus.fromRaw(recordStatus).name,
            "memberUids" to memberUids.distinct().take(20),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class AuditLogDoc(
    @DocumentId val id: String = "",
    val auditId: String = "",
    val familyId: String = "",
    val actorUserId: String = "",
    val entityCollection: String = "",
    val entityId: String = "",
    val action: String = AuditAction.CREATE.name,
    val summary: String = "",
    val afterHash: String? = null,
    val deviceId: String? = null,
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "auditId" to auditId,
            "familyId" to familyId,
            "actorUserId" to actorUserId,
            "entityCollection" to entityCollection.trim().take(40),
            "entityId" to entityId,
            "action" to AuditAction.fromRaw(action).name,
            "summary" to summary.trim().take(240),
            "afterHash" to afterHash?.take(128)?.takeIf { it.isNotEmpty() },
            "deviceId" to deviceId?.take(128)?.takeIf { it.isNotEmpty() },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20),
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class CallSessionDoc(
    @DocumentId val id: String = "",
    val callId: String = "",
    val familyId: String = "",
    val callerUid: String = "",
    val calleeUid: String = "",
    val callerName: String = "",
    val calleeName: String = "",
    val participantUids: List<String> = emptyList(),
    val mediaType: String = CallMediaType.AUDIO.name,
    val status: String = CallStatus.RINGING.name,
    val offerSdp: String? = null,
    val offerType: String? = null,
    val answerSdp: String? = null,
    val answerType: String? = null,
    val iceRestartCount: Long = 0L,
    val networkQuality: String = CallConnectionQuality.UNKNOWN.name,
    val durationSeconds: Long = 0L,
    val endedByUid: String? = null,
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "callId" to callId,
            "familyId" to familyId,
            "callerUid" to callerUid,
            "calleeUid" to calleeUid,
            "callerName" to callerName.trim().take(80).ifBlank { "Member" },
            "calleeName" to calleeName.trim().take(80).ifBlank { "Member" },
            "participantUids" to listOf(callerUid, calleeUid).distinct(),
            "mediaType" to CallMediaType.fromRaw(mediaType).name,
            "status" to CallStatus.RINGING.name,
            "offerSdp" to offerSdp?.take(30_000)?.takeIf { it.isNotBlank() },
            "offerType" to offerType?.takeIf { it in setOf("offer", "pranswer", "answer", "rollback") },
            "iceRestartCount" to iceRestartCount.coerceIn(0L, 50L),
            "networkQuality" to CallConnectionQuality.fromRaw(networkQuality).name,
            "durationSeconds" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class IceCandidateDoc(
    @DocumentId val id: String = "",
    val candidateId: String = "",
    val callId: String = "",
    val familyId: String = "",
    val senderUid: String = "",
    val role: String = "CALLER",
    val sdpMid: String? = null,
    val sdpMLineIndex: Long = 0L,
    val candidate: String = "",
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "candidateId" to candidateId,
            "callId" to callId,
            "familyId" to familyId,
            "senderUid" to senderUid,
            "role" to (if (role.uppercase() == "CALLEE") "CALLEE" else "CALLER"),
            "sdpMid" to sdpMid?.take(64)?.takeIf { it.isNotBlank() },
            "sdpMLineIndex" to sdpMLineIndex.coerceIn(0L, 32L),
            "candidate" to candidate.take(2048),
            "participantUids" to participantUids.distinct().take(2),
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FamilyMeetingDoc(
    @DocumentId val id: String = "",
    val meetingId: String = "",
    val familyId: String = "",
    val roomName: String = "",
    val title: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val inviteCode: String = "",
    val inviteLink: String = "",
    val status: String = MeetingStatus.LIVE.name,
    val isScheduled: Boolean = false,
    val scheduledFor: Timestamp? = null,
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    val endedByUid: String? = null,
    val audioEnabledDefault: Boolean = true,
    val videoEnabledDefault: Boolean = true,
    val invitedUids: List<String> = emptyList(),
    val activeParticipantUids: List<String> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(scheduledForMillis: Long? = null): Map<String, Any> =
        mapOf(
            "meetingId" to meetingId,
            "familyId" to familyId,
            "roomName" to roomName.trim().take(128),
            "title" to title.trim().take(100).ifBlank { "Family Meet" },
            "hostUid" to hostUid,
            "hostName" to hostName.trim().take(80).ifBlank { "Host" },
            "inviteCode" to inviteCode.trim().uppercase().take(24),
            "inviteLink" to inviteLink.trim().take(300),
            "status" to MeetingStatus.fromRaw(status).name,
            "isScheduled" to isScheduled,
            "scheduledFor" to scheduledForMillis?.let { Timestamp(Date(it)) },
            "startedAt" to (if (status == MeetingStatus.LIVE.name) FieldValue.serverTimestamp() else null),
            "audioEnabledDefault" to audioEnabledDefault,
            "videoEnabledDefault" to videoEnabledDefault,
            "invitedUids" to invitedUids.distinct().take(20),
            "activeParticipantUids" to activeParticipantUids.distinct().take(20),
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20).takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class MeetingParticipantDoc(
    @DocumentId val id: String = "",
    val participantUid: String = "",
    val meetingId: String = "",
    val familyId: String = "",
    val displayName: String = "",
    val roleLabel: String? = null,
    val isHost: Boolean = false,
    val state: String = MeetingParticipantState.INVITED.name,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val screenSharing: Boolean = false,
    val mutedByHost: Boolean = false,
    val removedByHost: Boolean = false,
    val memberUids: List<String> = emptyList(),
    val joinedAt: Timestamp? = null,
    val leftAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toUpsertMap(joinedNow: Boolean = false): Map<String, Any> =
        mapOf(
            "participantUid" to participantUid,
            "meetingId" to meetingId,
            "familyId" to familyId,
            "displayName" to displayName.trim().take(80).ifBlank { "Member" },
            "roleLabel" to roleLabel?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "isHost" to isHost,
            "state" to MeetingParticipantState.fromRaw(state).name,
            "audioEnabled" to audioEnabled,
            "videoEnabled" to videoEnabled,
            "screenSharing" to screenSharing,
            "mutedByHost" to mutedByHost,
            "removedByHost" to removedByHost,
            "memberUids" to memberUids.distinct().take(20),
            "joinedAt" to if (joinedNow) FieldValue.serverTimestamp() else joinedAt,
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

object MeetInviteHelper {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generateMeetingId(): String = DatabaseIdentityHelper.generateRecordId("mtg")
    fun generateCallId(): String = DatabaseIdentityHelper.generateRecordId("call")
    fun generateCandidateId(): String = DatabaseIdentityHelper.generateRecordId("ice")

    fun buildRoomName(familyId: String, meetingId: String): String {
        val cleanFam = familyId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "").take(40)
        val cleanMeet = meetingId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "").take(48)
        return "ff_${cleanFam}_$cleanMeet"
    }

    fun generateInviteCode(): String {
        val partA = (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val partB = (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "FF-$partA-$partB"
    }

    fun buildInviteLink(familyId: String, meetingId: String, inviteCode: String): String =
        "https://gen-lang-client-0265273815.web.app/meet?familyId=${familyId.trim()}&meetingId=${meetingId.trim()}&code=${inviteCode.trim().uppercase()}"

    data class ParsedMeetInvite(
        val familyId: String? = null,
        val meetingId: String? = null,
        val codeOrIdToken: String = ""
    )

    fun parseInviteInput(rawInput: String): ParsedMeetInvite {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", "")
            val params = query.split("&")
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    if (idx > 0) kv.substring(0, idx) to kv.substring(idx + 1) else null
                }
                .toMap()
            return ParsedMeetInvite(
                familyId = params["familyId"]?.takeIf { it.isNotBlank() },
                meetingId = params["meetingId"]?.takeIf { it.isNotBlank() },
                codeOrIdToken = params["code"]?.uppercase()?.takeIf { it.isNotBlank() }
                    ?: params["meetingId"]
                    ?: trimmed
            )
        }
        return ParsedMeetInvite(
            meetingId = if (trimmed.startsWith("mtg_", ignoreCase = true)) trimmed else null,
            codeOrIdToken = trimmed.uppercase()
        )
    }
}

