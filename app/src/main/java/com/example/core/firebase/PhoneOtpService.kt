package com.example.core.firebase

import android.app.Activity
import com.example.core.model.AuthValidation
import com.example.core.model.PhoneOtpState
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Manages real Firebase Phone Authentication SMS OTP verification,
 * OTP expiration tracking, resend cooldowns, and PhoneAuthCredential sign-in.
 */
class PhoneOtpService(
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    private val functionsClient: AuthFunctionsClient = AuthFunctionsClient(auth)
) {
    private val _otpState = MutableStateFlow<PhoneOtpState>(PhoneOtpState.Idle)
    val otpState: StateFlow<PhoneOtpState> = _otpState.asStateFlow()

    private var lastResendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var lastVerifiedPhone: String? = null
    private var activeResendCooldownSeconds: Int = DEFAULT_RESEND_COOLDOWN_SEC
    private var activeOtpExpirySeconds: Int = DEFAULT_OTP_EXPIRY_SEC

    suspend fun sendVerificationCode(
        activity: Activity,
        rawPhoneNumber: String,
        deviceId: String,
        isResend: Boolean = false
    ) {
        val normalized = AuthValidation.normalizeE164Phone(rawPhoneNumber)
        if (!AuthValidation.isValidE164Phone(normalized)) {
            _otpState.value = PhoneOtpState.Error("Enter a valid international mobile number (e.g. +14155550123).")
            return
        }

        val current = _otpState.value
        if (isResend && current is PhoneOtpState.CodeSent) {
            val now = System.currentTimeMillis()
            if (now < current.resendAvailableAtMillis) {
                val waitSec = ((current.resendAvailableAtMillis - now) / 1000L).toInt().coerceAtLeast(1)
                _otpState.value = PhoneOtpState.Error(
                    message = "Please wait ${waitSec}s before requesting another SMS OTP.",
                    retryAfterSeconds = waitSec
                )
                return
            }
        }

        _otpState.value = PhoneOtpState.Sending(normalized)

        // Check server-side OTP abuse protection if backend is deployed
        if (functionsClient.isConfigured) {
            val guardResult = functionsClient.requestOtpGuard(normalized, deviceId)
            if (guardResult.isFailure) {
                val err = guardResult.exceptionOrNull()
                val retryAfter = (err as? AuthBackendException)?.retryAfterSeconds
                _otpState.value = PhoneOtpState.Error(
                    message = err?.message ?: "OTP request blocked by abuse protection.",
                    retryAfterSeconds = retryAfter
                )
                return
            }
            guardResult.getOrNull()?.let { guard ->
                activeResendCooldownSeconds = guard.cooldownSeconds.coerceAtLeast(30)
                activeOtpExpirySeconds = guard.otpExpirySeconds.coerceAtLeast(60)
            }
        }

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                completeWithCredentialAsync(normalized, credential, isAutoRetrieved = true)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                val friendly = mapPhoneAuthException(e)
                _otpState.value = PhoneOtpState.Error(friendly)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                lastResendToken = token
                val now = System.currentTimeMillis()
                _otpState.value = PhoneOtpState.CodeSent(
                    phoneNumber = normalized,
                    verificationId = verificationId,
                    sentAtEpochMillis = now,
                    expiresAtEpochMillis = now + activeOtpExpirySeconds * 1000L,
                    resendAvailableAtMillis = now + activeResendCooldownSeconds * 1000L
                )
            }

            override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                // Keep manual code entry active if auto-retrieval timed out before OTP expiry
            }
        }

        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(normalized)
            .setTimeout(activeOtpExpirySeconds.toLong(), TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (isResend && lastResendToken != null) {
            optionsBuilder.setForceResendingToken(lastResendToken!!)
        }

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    suspend fun verifyEnteredOtpCode(
        verificationId: String,
        smsCode: String,
        expectedPhone: String
    ): Result<PhoneOtpState.Verified> {
        val cleanCode = smsCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Enter the 6-digit SMS verification code."))
        }

        val current = _otpState.value
        if (current is PhoneOtpState.CodeSent && System.currentTimeMillis() > current.expiresAtEpochMillis) {
            _otpState.value = PhoneOtpState.Error("Verification code expired. Request a new SMS OTP.")
            return Result.failure(IllegalStateException("OTP code has expired."))
        }

        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, cleanCode)
            val result = completeWithPhoneCredential(expectedPhone, credential, isAutoRetrieved = false)
            _otpState.value = result
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = mapPhoneAuthException(e)
            _otpState.value = PhoneOtpState.Error(msg)
            Result.failure(IllegalStateException(msg, e))
        }
    }

    private fun completeWithCredentialAsync(
        phoneNumber: String,
        credential: PhoneAuthCredential,
        isAutoRetrieved: Boolean
    ) {
        // Triggered on main thread callback when Play Services auto-retrieves OTP
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val user = authResult.user ?: return@addOnSuccessListener
                user.getIdToken(false).addOnSuccessListener { tokenResult ->
                    val token = tokenResult.token.orEmpty()
                    lastVerifiedPhone = phoneNumber
                    _otpState.value = PhoneOtpState.Verified(
                        phoneNumber = phoneNumber,
                        uid = user.uid,
                        idToken = token,
                        isAutoRetrieved = isAutoRetrieved
                    )
                }.addOnFailureListener { err ->
                    _otpState.value = PhoneOtpState.Error(err.localizedMessage ?: "Failed to verify OTP session token.")
                }
            }
            .addOnFailureListener { err ->
                _otpState.value = PhoneOtpState.Error(mapPhoneAuthException(err))
            }
    }

    private suspend fun completeWithPhoneCredential(
        phoneNumber: String,
        credential: PhoneAuthCredential,
        isAutoRetrieved: Boolean
    ): PhoneOtpState.Verified {
        val authResult = auth.signInWithCredential(credential).await()
        val user = authResult.user
            ?: throw IllegalStateException("Firebase Phone Authentication did not return a user session.")
        val idToken = user.getIdToken(false).await().token
            ?: throw IllegalStateException("Unable to retrieve verified Firebase ID token.")
        lastVerifiedPhone = phoneNumber
        return PhoneOtpState.Verified(
            phoneNumber = phoneNumber,
            uid = user.uid,
            idToken = idToken,
            isAutoRetrieved = isAutoRetrieved
        )
    }

    fun resetState() {
        _otpState.value = PhoneOtpState.Idle
        lastResendToken = null
    }

    private fun mapPhoneAuthException(error: Throwable): String {
        val code = (error as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_PHONE_NUMBER" -> "Invalid mobile number format. Include country code (e.g. +1...)."
            "ERROR_INVALID_VERIFICATION_CODE" -> "Incorrect 6-digit SMS code. Check the code and try again."
            "ERROR_SESSION_EXPIRED" -> "SMS verification code expired. Please request a new OTP."
            "ERROR_TOO_MANY_REQUESTS", "ERROR_QUOTA_EXCEEDED" ->
                "SMS rate limit reached for this number or project. Please try again later."
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "Phone Authentication is not enabled in Firebase Console (Authentication -> Sign-in method -> Phone)."
            else -> error.localizedMessage ?: "SMS OTP verification failed."
        }
    }

    private companion object {
        const val DEFAULT_RESEND_COOLDOWN_SEC = 60
        const val DEFAULT_OTP_EXPIRY_SEC = 120
    }
}
