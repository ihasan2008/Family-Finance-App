package com.example.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

enum class FamilyRole(val label: String) {
    ADMIN("Admin"),
    MEMBER("Member");

    companion object {
        fun fromRaw(raw: String?): FamilyRole {
            return when (raw?.trim()?.uppercase()) {
                "OWNER", "ADMIN" -> ADMIN
                else -> MEMBER
            }
        }
    }
}

enum class InvitationStatus(val label: String) {
    ACTIVE("Active"),
    REVOKED("Revoked"),
    USED("Used");

    companion object {
        fun fromRaw(raw: String?): InvitationStatus {
            return entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ACTIVE
        }
    }
}

enum class JoinRequestStatus(val label: String) {
    PENDING_APPROVAL("Pending Admin Approval"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    companion object {
        fun fromRaw(raw: String?): JoinRequestStatus {
            return entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: PENDING_APPROVAL
        }
    }
}

object FamilySecurityHelper {
    private val secureRandom = SecureRandom()
    private const val FAMILY_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val familyIdRegex = Regex("^[A-Z0-9]{12}$")
    private val memberCardIdRegex = Regex("^[A-Z0-9]{16}$")

    fun generateRandomFamilyId(): String {
        val sb = StringBuilder(12)
        repeat(12) {
            sb.append(FAMILY_ID_ALPHABET[secureRandom.nextInt(FAMILY_ID_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun generateMemberCardId16(): String {
        val sb = StringBuilder(16)
        repeat(16) {
            sb.append(FAMILY_ID_ALPHABET[secureRandom.nextInt(FAMILY_ID_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun isValidMemberCardId16(raw: String): Boolean {
        return memberCardIdRegex.matches(raw.trim().uppercase())
    }

    fun normalizeFamilyIdInput(raw: String): String {
        return raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    fun isValidFamilyId12(raw: String): Boolean {
        return familyIdRegex.matches(normalizeFamilyIdInput(raw))
    }

    fun personalWorkspaceId(uid: String): String = "personal_${uid.trim()}"

    fun isPersonalWorkspaceId(familyId: String?): Boolean {
        return familyId?.startsWith("personal_") == true
    }

    fun resolveMemberIdFromPhone(phone: String?): String {
        return AuthValidation.resolveMemberIdFromPhone(phone.orEmpty())
    }

    fun generateInvitationId(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return "inv_" + bytes.joinToString("") { "%02x".format(it) }
    }

    data class GeneratedInvitationToken(
        val token: String,
        val tokenHash: String,
        val tokenPreview: String
    )

    fun generateInvitationToken(): GeneratedInvitationToken {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val token = "FFINV-${raw.substring(0, 6)}-${raw.substring(6, 12)}-${raw.substring(12, 18)}"
        val tokenHash = hashInvitationToken(token)
        val preview = "FFINV-${raw.substring(0, 4)}••••${raw.substring(14, 18)}"
        return GeneratedInvitationToken(
            token = token,
            tokenHash = tokenHash,
            tokenPreview = preview
        )
    }

    fun normalizeInvitationToken(rawToken: String): String {
        return rawToken.trim().uppercase()
    }

    fun hashInvitationToken(rawToken: String): String {
        val normalized = normalizeInvitationToken(rawToken)
        require(normalized.length in 12..64) {
            "Invitation token must be between 12 and 64 characters."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class CreateFamilyResponse(
    val ok: Boolean = false,
    val familyId: String = "",
    val name: String = "",
    val ownerId: String = ""
)

@Serializable
data class CreateInvitationResponse(
    val ok: Boolean = false,
    val invitationId: String = "",
    val token: String = "",
    val tokenPreview: String = "",
    val expiresAtMillis: Long = 0L
)

@Serializable
data class RequestJoinResponse(
    val ok: Boolean = false,
    val familyId: String = "",
    val familyName: String = "",
    val status: String = "PENDING_APPROVAL"
)

@Serializable
data class RecoveredFamilySummary(
    val familyId: String = "",
    val name: String = "",
    val currencyCode: String = "USD",
    val ownerId: String = "",
    val createdByUserId: String = "",
    val memberCount: Int = 1,
    val isCurrentOwner: Boolean = false,
    val isOriginalCreator: Boolean = false
)

@Serializable
data class RecoverFamiliesResponse(
    val ok: Boolean = false,
    val families: List<RecoveredFamilySummary> = emptyList()
)

@Serializable
data class SimpleFamilyActionResponse(
    val ok: Boolean = false
)
