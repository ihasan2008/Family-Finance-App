package com.example.core.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class LocalUserProfileEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val email: String,
    val phoneNumber: String? = null,
    val memberId: String = "",
    val maskedPhone: String? = null,
    val dateOfBirth: String? = null,
    val accountStatus: String = "ACTIVE",
    val passwordConfigured: Boolean = false,
    val passkeyCount: Int = 0,
    val newDeviceCheckEnabled: Boolean = true,
    val photoUrl: String? = null,
    val activeFamilyId: String? = null,
    val themePreference: String = "SYSTEM",
    val registeredAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "families")
data class LocalFamilyEntity(
    @PrimaryKey val familyId: String,
    val name: String,
    val ownerId: String,
    val createdByUserId: String = ownerId,
    val currencyCode: String,
    val memberUidsCsv: String,
    val adminUidsCsv: String = ownerId,
    val suspendedUidsCsv: String = "",
    val isPersonal: Boolean = false,
    val description: String? = null,
    val requireAdminApproval: Boolean = true,
    val memberChatEnabled: Boolean = true,
    val membersCanAddExpenses: Boolean = true,
    val budgetStartDay: Int = 1,
    val chatRetentionDays: Int = 90,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val syncStatus: String = "SYNCED",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val memberUids: List<String>
        get() = memberUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val adminUids: List<String>
        get() = adminUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val suspendedUids: List<String>
        get() = suspendedUidsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isSuspended(uid: String): Boolean = suspendedUids.contains(uid)

    fun isAdmin(uid: String): Boolean {
        return !isSuspended(uid) && (uid == ownerId || adminUids.contains(uid))
    }
}

@Entity(tableName = "family_members", primaryKeys = ["familyId", "userId"])
data class LocalFamilyMemberEntity(
    val familyId: String,
    val userId: String,
    val displayName: String,
    val memberId: String = "",
    val maskedPhone: String? = null,
    val role: String,
    val memberStatus: String = "ACTIVE",
    val relationLabel: String? = null,
    val canCreateRecords: Boolean = true,
    val canViewSharedFinances: Boolean = true,
    val canParticipateInChat: Boolean = true,
    val joinedAtMillis: Long = System.currentTimeMillis(),
    val syncStatus: String = "SYNCED",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val cardMemberId: String = ""
)

@Entity(tableName = "member_cards", primaryKeys = ["familyId", "userId"])
data class LocalMemberCardEntity(
    val familyId: String,
    val userId: String,
    val cardMemberId: String,
    val familyName: String,
    val displayName: String,
    val photoUrl: String? = null,
    val role: String = "MEMBER",
    val memberStatus: String = "ACTIVE",
    val joinedAtMillis: Long = System.currentTimeMillis(),
    val showAvailableBalance: Boolean = true,
    val showIncomeAndExpense: Boolean = true,
    val showFamilyContributions: Boolean = true,
    val showLoans: Boolean = true,
    val showSavings: Boolean = true,
    val showInvestments: Boolean = true,
    val showTransactionHistory: Boolean = true,
    val allowAdminDetailedAudit: Boolean = true,
    val syncStatus: String = "SYNCED",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "my_join_requests", primaryKeys = ["familyId", "userId"])
data class LocalMyJoinRequestEntity(
    val familyId: String,
    val userId: String,
    val familyName: String,
    val tokenPreview: String,
    val relationLabel: String,
    val status: String,
    val requestedAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)
