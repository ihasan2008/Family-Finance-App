package com.example.core.firebase

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.AuthLoginMethod
import com.example.core.model.AuthValidation
import com.example.core.model.ChangeMobileResponse
import com.example.core.model.DeviceSessionDoc
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.PhoneOtpState
import com.example.core.model.RegisterAccountResponse
import com.example.core.model.VerifyFamilyAdminResponse
import com.example.core.remote.attemptAutoSignIn
import com.example.core.remote.authStateFlow
import com.example.core.remote.onGoogleSignInClicked
import com.example.core.remote.signOut
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

sealed interface PasswordLoginOutcome {
    data class Authenticated(val uid: String, val sessionId: String) : PasswordLoginOutcome
    data class NewDeviceChallengeRequired(val uid: String, val message: String) : PasswordLoginOutcome
}

/**
 * Unified multi-method Firebase Authentication service module supporting:
 * 1. Google Sign-In via Credential Manager
 * 2. Firebase Phone Auth SMS OTP (registration, login, recovery, new-device verification)
 * 3. Trusted Cloud Functions Phone + scrypt Password Auth
 * 4. WebAuthn / FIDO2 Passkeys via Credential Manager platform authenticator
 * 5. Active Device Session tracking & remote revocation
 */
class AuthService(
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    val functionsClient: AuthFunctionsClient = AuthFunctionsClient(auth),
    val phoneOtpService: PhoneOtpService = PhoneOtpService(auth, functionsClient),
    val passkeyService: PasskeyService = PasskeyService(auth, functionsClient)
) {
    val authState: Flow<FirebaseUser?> = auth.authStateFlow()
    val otpState: StateFlow<PhoneOtpState> = phoneOtpService.otpState

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated session required before Firebase access.")
    }

    fun tryAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = onUnauthenticated,
            scope = scope
        )
    }

    fun signInWithGoogle(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {}
    ) {
        onGoogleSignInClicked(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError,
            scope = scope,
            onAuthCancelled = onAuthCancelled
        )
    }

    suspend fun sendSmsOtp(
        activity: Activity,
        prefsStore: ThemePreferencesStore,
        phoneNumber: String,
        isResend: Boolean = false
    ) {
        val deviceId = prefsStore.getOrCreateDeviceId()
        phoneOtpService.sendVerificationCode(
            activity = activity,
            rawPhoneNumber = phoneNumber,
            deviceId = deviceId,
            isResend = isResend
        )
    }

    suspend fun verifySmsOtpCode(
        verificationId: String,
        smsCode: String,
        phoneNumber: String,
        prefsStore: ThemePreferencesStore
    ): Result<PhoneOtpState.Verified> {
        val cleanPhone = AuthValidation.normalizeE164Phone(phoneNumber)
        val res = phoneOtpService.verifyEnteredOtpCode(verificationId, smsCode, cleanPhone)
        if (res.isSuccess) {
            prefsStore.setLastPhoneNumber(cleanPhone)
        }
        return res
    }

    suspend fun completePasswordlessRegistration(
        phoneNumber: String,
        displayName: String,
        dateOfBirthIso: String,
        prefsStore: ThemePreferencesStore
    ): Result<RegisterAccountResponse> {
        val cleanName = displayName.trim()
        if (cleanName.length < 2) {
            return Result.failure(IllegalArgumentException("Enter your full name (at least 2 characters)."))
        }
        val normalized = AuthValidation.normalizeE164Phone(phoneNumber)
        if (!AuthValidation.isValidE164Phone(normalized)) {
            return Result.failure(IllegalArgumentException("Enter a valid international mobile number (e.g. +8801712345678)."))
        }
        val validDob = AuthValidation.validateDateOfBirthIso(dateOfBirthIso)
            ?: return Result.failure(IllegalArgumentException("Enter a valid Date of Birth in YYYY-MM-DD format."))

        val deviceId = prefsStore.getOrCreateDeviceId()
        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return functionsClient.registerMemberPasswordless(
            phoneNumber = normalized,
            displayName = cleanName.take(80),
            dateOfBirth = validDob,
            deviceId = deviceId,
            sessionId = sessionId
        ).onSuccess {
            prefsStore.setCurrentSessionId(sessionId)
            prefsStore.setLastPhoneNumber(normalized)
        }
    }

    suspend fun changeVerifiedMobileNumber(
        newPhoneNumber: String,
        newPhoneIdToken: String,
        revokeOtherSessions: Boolean,
        prefsStore: ThemePreferencesStore
    ): Result<ChangeMobileResponse> {
        val normalized = AuthValidation.normalizeE164Phone(newPhoneNumber)
        if (!AuthValidation.isValidE164Phone(normalized)) {
            return Result.failure(IllegalArgumentException("Enter a valid E.164 mobile number."))
        }
        val keepSessionId = prefsStore.getCurrentSessionId()
        return functionsClient.changeVerifiedMobileNumber(
            newPhoneNumber = normalized,
            newPhoneIdToken = newPhoneIdToken,
            revokeOtherSessions = revokeOtherSessions,
            keepSessionId = keepSessionId
        ).onSuccess {
            prefsStore.setLastPhoneNumber(normalized)
        }
    }

    suspend fun verifyFamilyAdminLogin(
        familyId: String,
        familyPassword: String
    ): Result<VerifyFamilyAdminResponse> {
        val cleanFamilyId = FamilySecurityHelper.normalizeFamilyIdInput(familyId)
        if (!FamilySecurityHelper.isValidFamilyId12(cleanFamilyId)) {
            return Result.failure(IllegalArgumentException("Family ID must be 12 uppercase alphanumeric characters."))
        }
        if (familyPassword.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter the Family Password."))
        }
        return functionsClient.verifyFamilyAdminLogin(
            familyId = cleanFamilyId,
            familyPassword = familyPassword
        )
    }

    suspend fun completePhonePasswordRegistration(
        phoneNumber: String,
        password: String,
        confirmPassword: String,
        displayName: String,
        prefsStore: ThemePreferencesStore
    ): Result<String> {
        val normalized = AuthValidation.normalizeE164Phone(phoneNumber)
        if (!AuthValidation.isValidE164Phone(normalized)) {
            return Result.failure(IllegalArgumentException("Enter a valid international mobile number."))
        }
        val strength = AuthValidation.evaluatePasswordStrength(password)
        if (!strength.isAcceptable) {
            return Result.failure(IllegalArgumentException("Password must be 8+ characters with upper, lower, digit, and symbol."))
        }
        if (password != confirmPassword) {
            return Result.failure(IllegalArgumentException("Password confirmation does not match."))
        }

        val deviceId = prefsStore.getOrCreateDeviceId()
        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return functionsClient.registerWithPhonePassword(
            phoneNumber = normalized,
            password = password,
            displayName = displayName.trim().ifBlank { "Family Member" },
            deviceId = deviceId,
            sessionId = sessionId
        ).map { resp ->
            prefsStore.setCurrentSessionId(sessionId)
            prefsStore.setLastPhoneNumber(normalized)
            resp.uid
        }
    }

    suspend fun loginWithPhonePassword(
        phoneNumber: String,
        password: String,
        prefsStore: ThemePreferencesStore,
        otpIdToken: String? = null
    ): Result<PasswordLoginOutcome> {
        val normalized = AuthValidation.normalizeE164Phone(phoneNumber)
        if (!AuthValidation.isValidE164Phone(normalized)) {
            return Result.failure(IllegalArgumentException("Enter a valid E.164 mobile number (e.g. +14155550123)."))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter your account password."))
        }
        val deviceId = prefsStore.getOrCreateDeviceId()
        val result = functionsClient.loginWithPhonePassword(
            phoneNumber = normalized,
            password = password,
            deviceId = deviceId,
            otpIdToken = otpIdToken
        )

        val err = result.exceptionOrNull()
        if (err is AuthBackendException && err.code == "NEW_DEVICE_REQUIRED") {
            return Result.success(
                PasswordLoginOutcome.NewDeviceChallengeRequired(
                    uid = err.uid.orEmpty(),
                    message = err.message
                )
            )
        }
        val data = result.getOrElse { return Result.failure(it) }
        return try {
            auth.signInWithCustomToken(data.customToken).await()
            prefsStore.setCurrentSessionId(data.sessionId)
            prefsStore.setLastPhoneNumber(normalized)
            Result.success(PasswordLoginOutcome.Authenticated(uid = data.uid, sessionId = data.sessionId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetPasswordWithVerifiedOtp(
        phoneNumber: String,
        newPassword: String,
        confirmPassword: String,
        prefsStore: ThemePreferencesStore
    ): Result<String> {
        val normalized = AuthValidation.normalizeE164Phone(phoneNumber)
        val strength = AuthValidation.evaluatePasswordStrength(newPassword)
        if (!strength.isAcceptable) {
            return Result.failure(IllegalArgumentException("New password must be 8+ characters with upper, lower, digit, and symbol."))
        }
        if (newPassword != confirmPassword) {
            return Result.failure(IllegalArgumentException("Password confirmation does not match."))
        }
        val resp = functionsClient.resetPasswordWithOtp(normalized, newPassword)
            .getOrElse { return Result.failure(it) }
        return try {
            auth.signInWithCustomToken(resp.customToken).await()
            prefsStore.setLastPhoneNumber(normalized)
            Result.success(resp.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithPasskey(
        activity: Activity,
        credentialManager: CredentialManager,
        phoneNumberHint: String?,
        prefsStore: ThemePreferencesStore
    ): Result<PasskeyLoginOutcome> {
        val cleanHint = phoneNumberHint?.takeIf { it.isNotBlank() }?.let {
            AuthValidation.normalizeE164Phone(it)
        }
        val deviceId = prefsStore.getOrCreateDeviceId()
        return passkeyService.signInWithPasskey(
            activity = activity,
            credentialManager = credentialManager,
            phoneNumberHint = cleanHint,
            deviceId = deviceId
        ).onSuccess { outcome ->
            prefsStore.setCurrentSessionId(outcome.sessionId)
        }
    }

    suspend fun enrollPasskey(
        activity: Activity,
        credentialManager: CredentialManager,
        label: String
    ): Result<String> {
        return passkeyService.enrollPasskey(activity, credentialManager, label)
    }

    suspend fun ensureSessionTrackedInFirestore(
        db: FirebaseFirestore,
        firestoreService: FirestoreService,
        prefsStore: ThemePreferencesStore,
        user: FirebaseUser
    ): String {
        val existingId = prefsStore.getCurrentSessionId()
        val sessionId = existingId ?: "sess_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        if (existingId == null) {
            prefsStore.setCurrentSessionId(sessionId)
        }
        val deviceId = prefsStore.getOrCreateDeviceId()
        val method = when {
            user.providerData.any { it.providerId == "phone" } -> AuthLoginMethod.PHONE_OTP
            else -> AuthLoginMethod.GOOGLE
        }
        val docRef = db.collection("users").document(user.uid).collection("sessions").document(sessionId)
        val snap = runCatching { docRef.get().await() }.getOrNull()
        if (snap == null || !snap.exists()) {
            firestoreService.setDocument(
                docRef = docRef,
                payload = DeviceSessionDoc(
                    sessionId = sessionId,
                    userId = user.uid,
                    deviceId = deviceId,
                    deviceLabel = functionsClient.currentDeviceLabel(),
                    platform = "Android",
                    authMethod = method.name,
                    isTrustedDevice = true,
                    revoked = false
                ).toCreateMap(),
                pathForLogs = docRef.path
            )
        } else {
            firestoreService.updateDocument(
                docRef = docRef,
                updates = mapOf("lastActiveAt" to FieldValue.serverTimestamp()),
                pathForLogs = docRef.path
            )
        }
        return sessionId
    }

    fun signOutSession(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        phoneOtpService.resetState()
        signOut(
            credentialManager = credentialManager,
            scope = scope,
            onSignOutComplete = onSignOutComplete
        )
    }
}
