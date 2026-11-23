package com.example.core.local.abstraction

import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalProposalVoteEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.LocalUserProfileEntity
import com.example.core.local.security.AndroidKeyStoreCipher
import com.example.core.local.security.LocalDataCipher
import com.example.core.model.MoneyCalculator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LocalEntityCipherMapper(
    private val cipher: LocalDataCipher = AndroidKeyStoreCipher()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encrypt(e: LocalUserProfileEntity): LocalUserProfileEntity = e.copy(
        dateOfBirth = cipher.encryptNullable(e.dateOfBirth)
    )

    fun decrypt(e: LocalUserProfileEntity): LocalUserProfileEntity = e.copy(
        dateOfBirth = cipher.decryptNullable(e.dateOfBirth)
    )

    fun encrypt(e: LocalTransactionEntity): LocalTransactionEntity = e.copy(
        description = cipher.encrypt(e.description),
        paymentMethod = cipher.encryptNullable(e.paymentMethod)
    )

    fun decrypt(e: LocalTransactionEntity): LocalTransactionEntity = e.copy(
        description = cipher.decrypt(e.description),
        paymentMethod = cipher.decryptNullable(e.paymentMethod)
    )

    fun encrypt(e: LocalLoanEntity): LocalLoanEntity = e.copy(
        description = cipher.encrypt(e.description),
        borrowerContact = cipher.encrypt(e.borrowerContact),
        lenderContact = cipher.encrypt(e.lenderContact)
    )

    fun decrypt(e: LocalLoanEntity): LocalLoanEntity = e.copy(
        description = cipher.decrypt(e.description),
        borrowerContact = cipher.decrypt(e.borrowerContact),
        lenderContact = cipher.decrypt(e.lenderContact)
    )

    fun encrypt(e: LocalSavingsEntity): LocalSavingsEntity = e.copy(
        description = cipher.encrypt(e.description)
    )

    fun decrypt(e: LocalSavingsEntity): LocalSavingsEntity = e.copy(
        description = cipher.decrypt(e.description)
    )

    fun encrypt(e: LocalInvestmentEntity): LocalInvestmentEntity = e.copy(
        description = cipher.encrypt(e.description)
    )

    fun decrypt(e: LocalInvestmentEntity): LocalInvestmentEntity = e.copy(
        description = cipher.decrypt(e.description)
    )

    fun encrypt(e: LocalBudgetEntity): LocalBudgetEntity = e.copy(
        description = cipher.encrypt(e.description)
    )

    fun decrypt(e: LocalBudgetEntity): LocalBudgetEntity = e.copy(
        description = cipher.decrypt(e.description)
    )

    fun encrypt(e: LocalChatMessageEntity): LocalChatMessageEntity = e.copy(
        messageText = cipher.encrypt(e.messageText)
    )

    fun decrypt(e: LocalChatMessageEntity): LocalChatMessageEntity = e.copy(
        messageText = cipher.decrypt(e.messageText)
    )

    fun encrypt(e: LocalNotificationEntity): LocalNotificationEntity = e.copy(
        title = cipher.encrypt(e.title),
        body = cipher.encrypt(e.body)
    )

    fun decrypt(e: LocalNotificationEntity): LocalNotificationEntity = e.copy(
        title = cipher.decrypt(e.title),
        body = cipher.decrypt(e.body)
    )

    fun encrypt(e: LocalCachedReportEntity): LocalCachedReportEntity = e.copy(
        reportJson = cipher.encrypt(e.reportJson)
    )

    fun decrypt(e: LocalCachedReportEntity): LocalCachedReportEntity = e.copy(
        reportJson = cipher.decrypt(e.reportJson)
    )

    fun encrypt(e: LocalSyncConflictEntity): LocalSyncConflictEntity = e.copy(
        localSummary = cipher.encrypt(e.localSummary),
        remoteSummary = cipher.encrypt(e.remoteSummary),
        localPayloadJson = cipher.encrypt(e.localPayloadJson),
        remotePayloadJson = cipher.encrypt(e.remotePayloadJson)
    )

    fun decrypt(e: LocalSyncConflictEntity): LocalSyncConflictEntity = e.copy(
        localSummary = cipher.decrypt(e.localSummary),
        remoteSummary = cipher.decrypt(e.remoteSummary),
        localPayloadJson = cipher.decrypt(e.localPayloadJson),
        remotePayloadJson = cipher.decrypt(e.remotePayloadJson)
    )

    fun encrypt(e: LocalCallRecordEntity): LocalCallRecordEntity = e.copy(
        callerName = cipher.encrypt(e.callerName),
        calleeName = cipher.encrypt(e.calleeName)
    )

    fun decrypt(e: LocalCallRecordEntity): LocalCallRecordEntity = e.copy(
        callerName = cipher.decrypt(e.callerName),
        calleeName = cipher.decrypt(e.calleeName)
    )

    fun encrypt(e: LocalMeetingEntity): LocalMeetingEntity = e.copy(
        title = cipher.encrypt(e.title),
        inviteLink = cipher.encrypt(e.inviteLink)
    )

    fun decrypt(e: LocalMeetingEntity): LocalMeetingEntity = e.copy(
        title = cipher.decrypt(e.title),
        inviteLink = cipher.decrypt(e.inviteLink)
    )

    fun encrypt(e: LocalProposalEntity): LocalProposalEntity = e.copy(
        description = cipher.encrypt(e.description),
        reason = cipher.encrypt(e.reason),
        relatedParty = cipher.encrypt(e.relatedParty),
        supportingDocName = cipher.encryptNullable(e.supportingDocName),
        cancelReason = cipher.encryptNullable(e.cancelReason)
    )

    fun decrypt(e: LocalProposalEntity): LocalProposalEntity = e.copy(
        description = cipher.decrypt(e.description),
        reason = cipher.decrypt(e.reason),
        relatedParty = cipher.decrypt(e.relatedParty),
        supportingDocName = cipher.decryptNullable(e.supportingDocName),
        cancelReason = cipher.decryptNullable(e.cancelReason)
    )

    fun encrypt(e: LocalProposalVoteEntity): LocalProposalVoteEntity = e.copy(
        comment = cipher.encryptNullable(e.comment)
    )

    fun decrypt(e: LocalProposalVoteEntity): LocalProposalVoteEntity = e.copy(
        comment = cipher.decryptNullable(e.comment)
    )

    fun summarizeTransaction(t: LocalTransactionEntity): String =
        "${t.transactionType} ${t.category}: ${MoneyCalculator.formatMinorUnits(t.effectiveAmountMinor(), t.currency)} (v${t.syncVersion})"

    fun summarizeLoan(l: LocalLoanEntity): String =
        "${l.loanDirection} ${l.counterparty}: rem ${MoneyCalculator.formatMinorUnits(l.effectiveRemainingMinor(), l.currency)} (v${l.syncVersion})"

    fun summarizeSavings(s: LocalSavingsEntity): String =
        "${s.goalName}: ${MoneyCalculator.formatMinorUnits(s.effectiveCurrentMinor(), s.currency)} / ${MoneyCalculator.formatMinorUnits(s.effectiveTargetMinor(), s.currency)} (v${s.syncVersion})"

    fun summarizeInvestment(i: LocalInvestmentEntity): String =
        "${i.assetName}: val ${MoneyCalculator.formatMinorUnits(i.effectiveCurrentValueMinor(), i.currency)} (v${i.syncVersion})"

    fun summarizeBudget(b: LocalBudgetEntity): String =
        "${b.periodKey} ${b.category}: limit ${MoneyCalculator.formatMinorUnits(b.effectiveLimitMinor(), b.currency)} (v${b.syncVersion})"

    fun encodeTransactionSnapshot(t: LocalTransactionEntity): String = json.encodeToString(
        TransactionSnapshot.serializer(),
        TransactionSnapshot(
            recordId = t.recordId,
            familyId = t.familyId,
            createdByUserId = t.createdByUserId,
            memberId = t.memberId,
            amount = t.amount,
            amountMinor = t.amountMinor,
            currency = t.currency,
            category = t.category,
            description = t.description,
            recordStatus = t.recordStatus,
            transactionType = t.transactionType,
            paymentMethod = t.paymentMethod,
            occurredAtMillis = t.occurredAtMillis,
            recurrenceFrequency = t.recurrenceFrequency,
            nextRecurrenceAtMillis = t.nextRecurrenceAtMillis,
            recurringParentId = t.recurringParentId,
            isRecurringTemplate = t.isRecurringTemplate,
            idempotencyKey = t.idempotencyKey,
            syncVersion = t.syncVersion,
            deviceId = t.deviceId,
            createdAtMillis = t.createdAtMillis,
            updatedAtMillis = t.updatedAtMillis
        )
    )

    fun decodeTransactionSnapshot(rawJson: String): LocalTransactionEntity? = runCatching {
        val s = json.decodeFromString(TransactionSnapshot.serializer(), rawJson)
        LocalTransactionEntity(
            recordId = s.recordId,
            familyId = s.familyId,
            createdByUserId = s.createdByUserId,
            memberId = s.memberId,
            amount = s.amount,
            amountMinor = s.amountMinor,
            currency = s.currency,
            category = s.category,
            description = s.description,
            recordStatus = s.recordStatus,
            transactionType = s.transactionType,
            paymentMethod = s.paymentMethod,
            occurredAtMillis = s.occurredAtMillis,
            recurrenceFrequency = s.recurrenceFrequency,
            nextRecurrenceAtMillis = s.nextRecurrenceAtMillis,
            recurringParentId = s.recurringParentId,
            isRecurringTemplate = s.isRecurringTemplate,
            idempotencyKey = s.idempotencyKey,
            syncStatus = "SYNCED",
            syncVersion = s.syncVersion,
            baseSyncVersion = s.syncVersion,
            deviceId = s.deviceId,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }.getOrNull()

    fun encodeLoanSnapshot(l: LocalLoanEntity): String = json.encodeToString(
        LoanSnapshot.serializer(),
        LoanSnapshot(
            recordId = l.recordId,
            familyId = l.familyId,
            createdByUserId = l.createdByUserId,
            amount = l.amount,
            amountMinor = l.amountMinor,
            remainingBalance = l.remainingBalance,
            remainingBalanceMinor = l.remainingBalanceMinor,
            interestRateApr = l.interestRateApr,
            currency = l.currency,
            category = l.category,
            description = l.description,
            recordStatus = l.recordStatus,
            loanDirection = l.loanDirection,
            counterparty = l.counterparty,
            borrowerName = l.borrowerName,
            borrowerContact = l.borrowerContact,
            lenderName = l.lenderName,
            lenderContact = l.lenderContact,
            totalInstallments = l.totalInstallments,
            paidInstallments = l.paidInstallments,
            installmentAmountMinor = l.installmentAmountMinor,
            startDateMillis = l.startDateMillis,
            dueDateMillis = l.dueDateMillis,
            idempotencyKey = l.idempotencyKey,
            syncVersion = l.syncVersion,
            deviceId = l.deviceId,
            createdAtMillis = l.createdAtMillis,
            updatedAtMillis = l.updatedAtMillis
        )
    )

    fun decodeLoanSnapshot(rawJson: String): LocalLoanEntity? = runCatching {
        val s = json.decodeFromString(LoanSnapshot.serializer(), rawJson)
        LocalLoanEntity(
            recordId = s.recordId,
            familyId = s.familyId,
            createdByUserId = s.createdByUserId,
            amount = s.amount,
            amountMinor = s.amountMinor,
            remainingBalance = s.remainingBalance,
            remainingBalanceMinor = s.remainingBalanceMinor,
            interestRateApr = s.interestRateApr,
            currency = s.currency,
            category = s.category,
            description = s.description,
            recordStatus = s.recordStatus,
            loanDirection = s.loanDirection,
            counterparty = s.counterparty,
            borrowerName = s.borrowerName,
            borrowerContact = s.borrowerContact,
            lenderName = s.lenderName,
            lenderContact = s.lenderContact,
            totalInstallments = s.totalInstallments,
            paidInstallments = s.paidInstallments,
            installmentAmountMinor = s.installmentAmountMinor,
            startDateMillis = s.startDateMillis,
            dueDateMillis = s.dueDateMillis,
            idempotencyKey = s.idempotencyKey,
            syncStatus = "SYNCED",
            syncVersion = s.syncVersion,
            baseSyncVersion = s.syncVersion,
            deviceId = s.deviceId,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }.getOrNull()

    fun encodeSavingsSnapshot(e: LocalSavingsEntity): String = json.encodeToString(
        SavingsSnapshot.serializer(),
        SavingsSnapshot(
            recordId = e.recordId,
            familyId = e.familyId,
            createdByUserId = e.createdByUserId,
            amount = e.amount,
            amountMinor = e.amountMinor,
            targetAmount = e.targetAmount,
            targetAmountMinor = e.targetAmountMinor,
            currency = e.currency,
            category = e.category,
            description = e.description,
            goalName = e.goalName,
            recordStatus = e.recordStatus,
            targetDateMillis = e.targetDateMillis,
            idempotencyKey = e.idempotencyKey,
            syncVersion = e.syncVersion,
            deviceId = e.deviceId,
            createdAtMillis = e.createdAtMillis,
            updatedAtMillis = e.updatedAtMillis
        )
    )

    fun decodeSavingsSnapshot(rawJson: String): LocalSavingsEntity? = runCatching {
        val s = json.decodeFromString(SavingsSnapshot.serializer(), rawJson)
        LocalSavingsEntity(
            recordId = s.recordId,
            familyId = s.familyId,
            createdByUserId = s.createdByUserId,
            amount = s.amount,
            amountMinor = s.amountMinor,
            targetAmount = s.targetAmount,
            targetAmountMinor = s.targetAmountMinor,
            currency = s.currency,
            category = s.category,
            description = s.description,
            goalName = s.goalName,
            recordStatus = s.recordStatus,
            targetDateMillis = s.targetDateMillis,
            idempotencyKey = s.idempotencyKey,
            syncStatus = "SYNCED",
            syncVersion = s.syncVersion,
            baseSyncVersion = s.syncVersion,
            deviceId = s.deviceId,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }.getOrNull()

    fun encodeInvestmentSnapshot(e: LocalInvestmentEntity): String = json.encodeToString(
        InvestmentSnapshot.serializer(),
        InvestmentSnapshot(
            recordId = e.recordId,
            familyId = e.familyId,
            createdByUserId = e.createdByUserId,
            amount = e.amount,
            amountMinor = e.amountMinor,
            currentValue = e.currentValue,
            currentValueMinor = e.currentValueMinor,
            units = e.units,
            currency = e.currency,
            category = e.category,
            description = e.description,
            assetName = e.assetName,
            assetType = e.assetType,
            symbol = e.symbol,
            investedAtMillis = e.investedAtMillis,
            recordStatus = e.recordStatus,
            idempotencyKey = e.idempotencyKey,
            syncVersion = e.syncVersion,
            deviceId = e.deviceId,
            createdAtMillis = e.createdAtMillis,
            updatedAtMillis = e.updatedAtMillis
        )
    )

    fun decodeInvestmentSnapshot(rawJson: String): LocalInvestmentEntity? = runCatching {
        val s = json.decodeFromString(InvestmentSnapshot.serializer(), rawJson)
        LocalInvestmentEntity(
            recordId = s.recordId,
            familyId = s.familyId,
            createdByUserId = s.createdByUserId,
            amount = s.amount,
            amountMinor = s.amountMinor,
            currentValue = s.currentValue,
            currentValueMinor = s.currentValueMinor,
            units = s.units,
            currency = s.currency,
            category = s.category,
            description = s.description,
            assetName = s.assetName,
            assetType = s.assetType,
            symbol = s.symbol,
            investedAtMillis = s.investedAtMillis,
            recordStatus = s.recordStatus,
            idempotencyKey = s.idempotencyKey,
            syncStatus = "SYNCED",
            syncVersion = s.syncVersion,
            baseSyncVersion = s.syncVersion,
            deviceId = s.deviceId,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }.getOrNull()

    fun encodeBudgetSnapshot(e: LocalBudgetEntity): String = json.encodeToString(
        BudgetSnapshot.serializer(),
        BudgetSnapshot(
            recordId = e.recordId,
            familyId = e.familyId,
            createdByUserId = e.createdByUserId,
            amount = e.amount,
            amountMinor = e.amountMinor,
            spentAmount = e.spentAmount,
            spentAmountMinor = e.spentAmountMinor,
            alertThresholdPercent = e.alertThresholdPercent,
            currency = e.currency,
            category = e.category,
            description = e.description,
            periodKey = e.periodKey,
            budgetScope = e.budgetScope,
            recordStatus = e.recordStatus,
            idempotencyKey = e.idempotencyKey,
            syncVersion = e.syncVersion,
            deviceId = e.deviceId,
            createdAtMillis = e.createdAtMillis,
            updatedAtMillis = e.updatedAtMillis
        )
    )

    fun decodeBudgetSnapshot(rawJson: String): LocalBudgetEntity? = runCatching {
        val s = json.decodeFromString(BudgetSnapshot.serializer(), rawJson)
        LocalBudgetEntity(
            recordId = s.recordId,
            familyId = s.familyId,
            createdByUserId = s.createdByUserId,
            amount = s.amount,
            amountMinor = s.amountMinor,
            spentAmount = s.spentAmount,
            spentAmountMinor = s.spentAmountMinor,
            alertThresholdPercent = s.alertThresholdPercent,
            currency = s.currency,
            category = s.category,
            description = s.description,
            periodKey = s.periodKey,
            budgetScope = s.budgetScope,
            recordStatus = s.recordStatus,
            idempotencyKey = s.idempotencyKey,
            syncStatus = "SYNCED",
            syncVersion = s.syncVersion,
            baseSyncVersion = s.syncVersion,
            deviceId = s.deviceId,
            lastSyncedAtMillis = System.currentTimeMillis(),
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }.getOrNull()

    @Serializable
    private data class TransactionSnapshot(
        val recordId: String,
        val familyId: String,
        val createdByUserId: String,
        val memberId: String,
        val amount: Double,
        val amountMinor: Long,
        val currency: String,
        val category: String,
        val description: String,
        val recordStatus: String,
        val transactionType: String,
        val paymentMethod: String? = null,
        val occurredAtMillis: Long,
        val recurrenceFrequency: String,
        val nextRecurrenceAtMillis: Long? = null,
        val recurringParentId: String? = null,
        val isRecurringTemplate: Boolean,
        val idempotencyKey: String,
        val syncVersion: Int,
        val deviceId: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )

    @Serializable
    private data class LoanSnapshot(
        val recordId: String,
        val familyId: String,
        val createdByUserId: String,
        val amount: Double,
        val amountMinor: Long,
        val remainingBalance: Double,
        val remainingBalanceMinor: Long,
        val interestRateApr: Double? = null,
        val currency: String,
        val category: String,
        val description: String,
        val recordStatus: String,
        val loanDirection: String,
        val counterparty: String,
        val borrowerName: String,
        val borrowerContact: String,
        val lenderName: String,
        val lenderContact: String,
        val totalInstallments: Int,
        val paidInstallments: Int,
        val installmentAmountMinor: Long,
        val startDateMillis: Long? = null,
        val dueDateMillis: Long? = null,
        val idempotencyKey: String,
        val syncVersion: Int,
        val deviceId: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )

    @Serializable
    private data class SavingsSnapshot(
        val recordId: String,
        val familyId: String,
        val createdByUserId: String,
        val amount: Double,
        val amountMinor: Long,
        val targetAmount: Double,
        val targetAmountMinor: Long,
        val currency: String,
        val category: String,
        val description: String,
        val goalName: String,
        val recordStatus: String,
        val targetDateMillis: Long? = null,
        val idempotencyKey: String,
        val syncVersion: Int,
        val deviceId: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )

    @Serializable
    private data class InvestmentSnapshot(
        val recordId: String,
        val familyId: String,
        val createdByUserId: String,
        val amount: Double,
        val amountMinor: Long,
        val currentValue: Double,
        val currentValueMinor: Long,
        val units: Double? = null,
        val currency: String,
        val category: String,
        val description: String,
        val assetName: String,
        val assetType: String,
        val symbol: String? = null,
        val investedAtMillis: Long,
        val recordStatus: String,
        val idempotencyKey: String,
        val syncVersion: Int,
        val deviceId: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )

    @Serializable
    private data class BudgetSnapshot(
        val recordId: String,
        val familyId: String,
        val createdByUserId: String,
        val amount: Double,
        val amountMinor: Long,
        val spentAmount: Double,
        val spentAmountMinor: Long,
        val alertThresholdPercent: Int,
        val currency: String,
        val category: String,
        val description: String,
        val periodKey: String,
        val budgetScope: String,
        val recordStatus: String,
        val idempotencyKey: String,
        val syncVersion: Int,
        val deviceId: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )
}
