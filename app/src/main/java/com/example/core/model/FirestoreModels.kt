package com.example.core.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

@Keep
@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val phoneNumber: String? = null,
    val memberId: String = "",
    val maskedPhone: String? = null,
    val dateOfBirth: String? = null,
    val accountStatus: String = AccountStatus.ACTIVE.name,
    val passwordConfigured: Boolean = false,
    val passkeyCount: Long = 0L,
    val newDeviceCheckEnabled: Boolean = true,
    val photoUrl: String? = null,
    val activeFamilyId: String? = null,
    val themePreference: String = ThemeMode.SYSTEM.name,
    @ServerTimestamp val registeredAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "userId" to userId,
            "displayName" to displayName.take(80).ifBlank { "Family Member" },
            "email" to email.take(120).ifBlank { "member@familyfinance.app" },
            "phoneNumber" to phoneNumber?.takeIf { AuthValidation.isValidE164Phone(it) },
            "memberId" to memberId.takeIf { it.isNotBlank() },
            "maskedPhone" to maskedPhone?.takeIf { it.isNotBlank() },
            "dateOfBirth" to dateOfBirth?.let { AuthValidation.validateDateOfBirthIso(it) },
            "accountStatus" to AccountStatus.fromRaw(accountStatus).name,
            "passwordConfigured" to passwordConfigured,
            "passkeyCount" to passkeyCount,
            "newDeviceCheckEnabled" to newDeviceCheckEnabled,
            "photoUrl" to photoUrl?.take(500),
            "activeFamilyId" to activeFamilyId?.takeIf { it.isNotBlank() },
            "themePreference" to ThemeMode.fromRaw(themePreference).name,
            "registeredAt" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class PasskeyCredentialDoc(
    @DocumentId val id: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val label: String = "Device Passkey",
    val rpId: String = "",
    val publicKeyBase64Url: String = "",
    val counter: Long = 0L,
    val backedUp: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastUsedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DeviceSessionDoc(
    @DocumentId val id: String = "",
    val sessionId: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val deviceLabel: String = "",
    val platform: String = "Android",
    val authMethod: String = AuthLoginMethod.GOOGLE.name,
    val isTrustedDevice: Boolean = true,
    val revoked: Boolean = false,
    val revokedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastActiveAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "userId" to userId,
        "deviceId" to deviceId.take(128),
        "deviceLabel" to deviceLabel.take(80).ifBlank { "Android Device" },
        "platform" to platform.take(32).ifBlank { "Android" },
        "authMethod" to AuthLoginMethod.fromRaw(authMethod).name,
        "isTrustedDevice" to isTrustedDevice,
        "revoked" to false,
        "createdAt" to FieldValue.serverTimestamp(),
        "lastActiveAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class FamilyDoc(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val createdByUserId: String = "",
    val currencyCode: String = "USD",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val suspendedUids: List<String> = emptyList(),
    val isPersonal: Boolean = false,
    val description: String? = null,
    val requireAdminApproval: Boolean = true,
    val memberChatEnabled: Boolean = true,
    val membersCanAddExpenses: Boolean = true,
    val budgetStartDay: Long = 1L,
    val chatRetentionDays: Long = 90L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "name" to name.trim().take(60),
            "ownerId" to ownerId,
            "createdByUserId" to createdByUserId.ifBlank { ownerId },
            "currencyCode" to currencyCode.trim().uppercase().take(6).ifBlank { "USD" },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.ifEmpty { listOf(ownerId) }.distinct().take(1),
            "suspendedUids" to suspendedUids.distinct().take(20),
            "isPersonal" to isPersonal,
            "description" to description?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            "requireAdminApproval" to true,
            "memberChatEnabled" to memberChatEnabled,
            "membersCanAddExpenses" to membersCanAddExpenses,
            "budgetStartDay" to budgetStartDay.coerceIn(1L, 28L),
            "chatRetentionDays" to chatRetentionDays.coerceIn(0L, 3650L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FcmTokenDoc(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val token: String = "",
    val platform: String = "Android",
    val notificationsEnabled: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toUpsertMap(): Map<String, Any> = mapOf(
        "deviceId" to deviceId.take(128),
        "token" to token.take(4096),
        "platform" to platform.take(32).ifBlank { "Android" },
        "notificationsEnabled" to notificationsEnabled,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class FamilyMemberDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val familyId: String = "",
    val displayName: String = "",
    val memberId: String = "",
    val maskedPhone: String? = null,
    val role: String = FamilyRole.MEMBER.name,
    val memberStatus: String = "ACTIVE",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val relationLabel: String? = null,
    val canCreateRecords: Boolean = true,
    val canViewSharedFinances: Boolean = true,
    val canParticipateInChat: Boolean = true,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val cardMemberId: String = ""
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "userId" to userId,
            "familyId" to familyId,
            "displayName" to displayName.trim().take(80).ifBlank { "Family Member" },
            "memberId" to memberId.trim().take(40),
            "cardMemberId" to cardMemberId.trim().uppercase().takeIf { FamilySecurityHelper.isValidMemberCardId16(it) },
            "maskedPhone" to maskedPhone?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "role" to FamilyRole.fromRaw(role).name,
            "memberStatus" to memberStatus.trim().ifBlank { "ACTIVE" },
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20),
            "relationLabel" to relationLabel?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "canCreateRecords" to canCreateRecords,
            "canViewSharedFinances" to canViewSharedFinances,
            "canParticipateInChat" to canParticipateInChat,
            "joinedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FamilyInviteLookupDoc(
    @DocumentId val id: String = "",
    val tokenHash: String = "",
    val invitationId: String = "",
    val familyId: String = "",
    val familyName: String = "",
    val createdByUid: String = "",
    val targetRole: String = FamilyRole.MEMBER.name,
    val inviteeHint: String? = null,
    val status: String = InvitationStatus.ACTIVE.name,
    val maxUses: Long = 1L,
    val usedCount: Long = 0L,
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(expiresAtMillis: Long): Map<String, Any> =
        mapOf(
            "tokenHash" to tokenHash,
            "invitationId" to invitationId,
            "familyId" to familyId,
            "familyName" to familyName.trim().take(60),
            "createdByUid" to createdByUid,
            "targetRole" to FamilyRole.fromRaw(targetRole).name,
            "inviteeHint" to inviteeHint?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            "status" to InvitationStatus.ACTIVE.name,
            "maxUses" to maxUses.coerceIn(1L, 10L),
            "usedCount" to 0L,
            "expiresAt" to Timestamp(Date(expiresAtMillis)),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FamilyInvitationDoc(
    @DocumentId val id: String = "",
    val invitationId: String = "",
    val familyId: String = "",
    val tokenHash: String = "",
    val token: String? = null,
    val tokenPreview: String = "",
    val targetRole: String = FamilyRole.MEMBER.name,
    val inviteeHint: String? = null,
    val status: String = InvitationStatus.ACTIVE.name,
    val maxUses: Long = 1L,
    val usedCount: Long = 0L,
    val createdByUid: String = "",
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(expiresAtMillis: Long): Map<String, Any> =
        mapOf(
            "invitationId" to invitationId,
            "familyId" to familyId,
            "tokenHash" to tokenHash,
            "token" to token?.take(64),
            "tokenPreview" to tokenPreview.take(40),
            "targetRole" to FamilyRole.fromRaw(targetRole).name,
            "inviteeHint" to inviteeHint?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            "status" to InvitationStatus.ACTIVE.name,
            "maxUses" to maxUses.coerceIn(1L, 10L),
            "usedCount" to 0L,
            "createdByUid" to createdByUid,
            "expiresAt" to Timestamp(Date(expiresAtMillis)),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class FamilyJoinRequestDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val familyId: String = "",
    val displayName: String = "",
    val memberId: String = "",
    val maskedPhone: String? = null,
    val authMethod: String = "INVITATION_TOKEN",
    val contactSummary: String? = null,
    val relationLabel: String? = null,
    val invitationId: String = "",
    val tokenHash: String = "",
    val requestedRole: String = FamilyRole.MEMBER.name,
    val status: String = JoinRequestStatus.PENDING_APPROVAL.name,
    val reviewedByUid: String? = null,
    val reviewedAt: Timestamp? = null,
    @ServerTimestamp val requestedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "userId" to userId,
            "familyId" to familyId,
            "displayName" to displayName.trim().take(80).ifBlank { "Family Member" },
            "memberId" to memberId.trim().take(40),
            "maskedPhone" to maskedPhone?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "authMethod" to authMethod.trim().take(40).ifBlank { "INVITATION_TOKEN" },
            "contactSummary" to contactSummary?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
            "relationLabel" to relationLabel?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            "invitationId" to invitationId,
            "tokenHash" to tokenHash,
            "requestedRole" to FamilyRole.fromRaw(requestedRole).name,
            "status" to JoinRequestStatus.PENDING_APPROVAL.name,
            "requestedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class MembershipNoticeDoc(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val familyName: String = "",
    val status: String = "",
    val reason: String? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MemberCardDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val familyId: String = "",
    val cardMemberId: String = "",
    val familyName: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val role: String = FamilyRole.MEMBER.name,
    val memberStatus: String = "ACTIVE",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val showAvailableBalance: Boolean = true,
    val showIncomeAndExpense: Boolean = true,
    val showFamilyContributions: Boolean = true,
    val showLoans: Boolean = true,
    val showSavings: Boolean = true,
    val showInvestments: Boolean = true,
    val showTransactionHistory: Boolean = true,
    val allowAdminDetailedAudit: Boolean = true,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> =
        mapOf(
            "userId" to userId,
            "familyId" to familyId,
            "cardMemberId" to cardMemberId.trim().uppercase(),
            "familyName" to familyName.trim().take(60).ifBlank { "Family Workspace" },
            "displayName" to displayName.trim().take(80).ifBlank { "Family Member" },
            "photoUrl" to photoUrl?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
            "role" to FamilyRole.fromRaw(role).name,
            "memberStatus" to if (memberStatus.equals("SUSPENDED", ignoreCase = true)) "SUSPENDED" else "ACTIVE",
            "memberUids" to memberUids.distinct().take(20),
            "adminUids" to adminUids.distinct().take(20),
            "showAvailableBalance" to showAvailableBalance,
            "showIncomeAndExpense" to showIncomeAndExpense,
            "showFamilyContributions" to showFamilyContributions,
            "showLoans" to showLoans,
            "showSavings" to showSavings,
            "showInvestments" to showInvestments,
            "showTransactionHistory" to showTransactionHistory,
            "allowAdminDetailedAudit" to allowAdminDetailedAudit,
            "joinedAt" to (joinedAt ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>

    fun toPrivacyUpdateMap(): Map<String, Any> = mapOf(
        "showAvailableBalance" to showAvailableBalance,
        "showIncomeAndExpense" to showIncomeAndExpense,
        "showFamilyContributions" to showFamilyContributions,
        "showLoans" to showLoans,
        "showSavings" to showSavings,
        "showInvestments" to showInvestments,
        "showTransactionHistory" to showTransactionHistory,
        "allowAdminDetailedAudit" to allowAdminDetailedAudit,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

