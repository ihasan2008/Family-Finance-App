package com.example.core.model

import kotlinx.serialization.Serializable

enum class AccountStatus(val label: String) {
    ACTIVE("Active"),
    PENDING_VERIFICATION("Pending Verification"),
    LOCKED("Locked"),
    SUSPENDED("Suspended");

    companion object {
        fun fromRaw(raw: String?): AccountStatus {
            return entries.firstOrNull { it.name == raw } ?: ACTIVE
        }
    }
}

enum class AuthLoginMethod(val label: String) {
    PHONE_OTP("Mobile + SMS OTP"),
    PASSKEY("Passkey (Biometrics / PIN)"),
    GOOGLE("Google Account"),
    FAMILY_ADMIN("Family Admin Login");

    companion object {
        fun fromRaw(raw: String?): AuthLoginMethod {
            return entries.firstOrNull { it.name == raw } ?: PHONE_OTP
        }
    }
}

enum class AuthScreenMode {
    WELCOME,
    LOGIN,
    REGISTER,
    RECOVERY_RESET,
    FAMILY_ADMIN_LOGIN,
    NEW_DEVICE_VERIFY
}

enum class RegistrationStep(val stepNumber: Int, val title: String) {
    ENTER_DETAILS(1, "Profile & Mobile"),
    VERIFY_OTP(2, "Verify SMS OTP"),
    ACCOUNT_READY(3, "Account Created")
}

enum class RecoveryStep(val stepNumber: Int, val title: String) {
    ENTER_MOBILE(1, "Registered Mobile"),
    VERIFY_OTP(2, "Verify SMS OTP"),
    COMPLETED(3, "Recovery & Passkey")
}

enum class FamilyMembershipState(val label: String) {
    NO_FAMILY("Personal Workspace Only"),
    PENDING_REQUEST("Pending Admin Approval"),
    ACTIVE_MEMBER("Active Family Member"),
    SUSPENDED("Suspended from Family"),
    REMOVED("Removed from Family")
}

enum class WorkspaceMode(val label: String) {
    PERSONAL("Personal Finance"),
    FAMILY("Family Finance")
}

data class PasswordStrengthResult(
    val score: Int,
    val hasMinLength: Boolean,
    val hasUppercase: Boolean,
    val hasLowercase: Boolean,
    val hasDigit: Boolean,
    val hasSpecialChar: Boolean,
    val label: String
) {
    val isAcceptable: Boolean
        get() = hasMinLength && hasUppercase && hasLowercase && hasDigit && hasSpecialChar
}

object AuthValidation {
    private val e164Regex = Regex("^\\+[1-9][0-9]{6,14}$")
    private val dobIsoRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun normalizeE164Phone(raw: String): String {
        return raw.trim().replace(Regex("[\\s\\-().]"), "")
    }

    fun isValidE164Phone(raw: String): Boolean {
        val normalized = normalizeE164Phone(raw)
        return e164Regex.matches(normalized)
    }

    fun resolveMemberIdFromPhone(rawPhone: String): String {
        val normalized = normalizeE164Phone(rawPhone)
        return if (isValidE164Phone(normalized)) normalized else ""
    }

    fun maskPhoneNumber(rawPhone: String?): String {
        val normalized = normalizeE164Phone(rawPhone.orEmpty())
        if (normalized.isBlank()) return "Verified Member"
        if (normalized.length <= 6) return "${normalized.take(2)}••••"
        val prefix = normalized.take(minOf(6, normalized.length - 4))
        val suffix = normalized.takeLast(4)
        return "$prefix••••$suffix"
    }

    /**
     * Validates an ISO calendar date string (YYYY-MM-DD) ensuring calendar correctness,
     * leap-year handling, and age bounds (1900-01-01 to today, minimum age 13 recommended or valid past DOB).
     */
    fun validateDateOfBirthIso(raw: String): String? {
        val clean = raw.trim()
        if (!dobIsoRegex.matches(clean)) return null
        val parts = clean.split("-")
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 1900 || month !in 1..12 || day < 1) return null
        val isLeap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeap) 29 else 28
            else -> return null
        }
        if (day > maxDay) return null
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val dobCal = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (dobCal.after(cal)) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    fun evaluatePasswordStrength(password: String): PasswordStrengthResult {
        val minLen = password.length in 8..128
        val upper = password.any { it.isUpperCase() }
        val lower = password.any { it.isLowerCase() }
        val digit = password.any { it.isDigit() }
        val special = password.any { !it.isLetterOrDigit() }

        val checksPassed = listOf(minLen, upper, lower, digit, special).count { it }
        val score = when {
            password.isEmpty() -> 0
            !minLen -> 1
            checksPassed <= 2 -> 1
            checksPassed == 3 -> 2
            checksPassed == 4 -> 3
            else -> 4
        }
        val label = when (score) {
            0 -> "Enter password"
            1 -> "Weak"
            2 -> "Fair"
            3 -> "Good"
            else -> "Strong"
        }
        return PasswordStrengthResult(
            score = score,
            hasMinLength = minLen,
            hasUppercase = upper,
            hasLowercase = lower,
            hasDigit = digit,
            hasSpecialChar = special,
            label = label
        )
    }

    fun evaluateFamilyPasswordStrength(password: String): PasswordStrengthResult =
        evaluatePasswordStrength(password)
}

sealed interface PhoneOtpState {
    data object Idle : PhoneOtpState
    data class Sending(val phoneNumber: String) : PhoneOtpState
    data class CodeSent(
        val phoneNumber: String,
        val verificationId: String,
        val sentAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val resendAvailableAtMillis: Long
    ) : PhoneOtpState
    data class Verified(
        val phoneNumber: String,
        val uid: String,
        val idToken: String,
        val isAutoRetrieved: Boolean
    ) : PhoneOtpState
    data class Error(val message: String, val retryAfterSeconds: Int? = null) : PhoneOtpState
}

@Serializable
data class ApiErrorResponse(
    val ok: Boolean = false,
    val code: String = "AUTH_ERROR",
    val message: String = "Authentication request failed.",
    val retryAfterSeconds: Int? = null,
    val uid: String? = null
)

@Serializable
data class OtpGuardResponse(
    val ok: Boolean = false,
    val cooldownSeconds: Int = 60,
    val otpExpirySeconds: Int = 120
)

@Serializable
data class RegisterAccountResponse(
    val ok: Boolean = false,
    val uid: String = "",
    val memberId: String = "",
    val maskedPhone: String = "",
    val accountStatus: String = "ACTIVE"
)

@Serializable
data class ChangeMobileResponse(
    val ok: Boolean = false,
    val uid: String = "",
    val memberId: String = "",
    val maskedPhone: String = ""
)

@Serializable
data class VerifyFamilyAdminResponse(
    val ok: Boolean = false,
    val familyId: String = "",
    val role: String = "ADMIN"
)

@Serializable
data class CustomTokenLoginResponse(
    val ok: Boolean = false,
    val uid: String = "",
    val customToken: String = "",
    val sessionId: String = ""
)

@Serializable
data class BeginPasskeyRegResponse(
    val ok: Boolean = false,
    val optionsJson: String = ""
)

@Serializable
data class FinishPasskeyRegResponse(
    val ok: Boolean = false,
    val credentialId: String = ""
)

@Serializable
data class BeginPasskeyAuthResponse(
    val ok: Boolean = false,
    val challengeId: String = "",
    val optionsJson: String = ""
)

@Serializable
data class RevokeSessionsResponse(
    val ok: Boolean = false,
    val revokedCount: Int = 0
)
