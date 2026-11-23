package com.example.core.firebase

import android.app.Activity
import android.util.Log
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

data class PasskeyLoginOutcome(
    val uid: String,
    val sessionId: String
)

/**
 * WebAuthn / FIDO2 Passkey authenticator service backed by Jetpack Credential Manager
 * and verified server-side via AuthFunctionsClient (@simplewebauthn/server).
 * Private passkey material stays strictly inside the platform authenticator (biometrics / face / PIN).
 */
class PasskeyService(
    private val auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    private val functionsClient: AuthFunctionsClient = AuthFunctionsClient(auth)
) {
    val relyingPartyId: String
        get() = BuildConfig.WEBAUTHN_RP_ID

    suspend fun enrollPasskey(
        activity: Activity,
        credentialManager: CredentialManager,
        label: String
    ): Result<String> {
        return try {
            val beginResult = functionsClient.beginPasskeyRegistration()
            val beginData = beginResult.getOrElse { return Result.failure(it) }
            if (beginData.optionsJson.isBlank()) {
                return Result.failure(IllegalStateException("Passkey registration options missing from server."))
            }

            val createRequest = CreatePublicKeyCredentialRequest(
                requestJson = beginData.optionsJson
            )
            val response: CreateCredentialResponse = credentialManager.createCredential(
                context = activity,
                request = createRequest
            )
            if (response !is CreatePublicKeyCredentialResponse) {
                return Result.failure(IllegalStateException("Unexpected credential response from platform authenticator."))
            }

            val finishResult = functionsClient.finishPasskeyRegistration(
                registrationResponseJson = response.registrationResponseJson,
                label = label.ifBlank { functionsClient.currentDeviceLabel() }
            )
            finishResult.map { it.credentialId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CreateCredentialCancellationException) {
            Log.w(TAG, "Passkey enrollment cancelled by user", e)
            Result.failure(IllegalStateException("Passkey enrollment was cancelled."))
        } catch (e: CreatePublicKeyCredentialDomException) {
            Log.e(TAG, "WebAuthn DOM error during passkey enrollment: ${e.domError}", e)
            Result.failure(IllegalStateException("WebAuthn verification error: ${e.message ?: "Check Digital Asset Links for ${BuildConfig.WEBAUTHN_RP_ID}"}"))
        } catch (e: CreateCredentialProviderConfigurationException) {
            Result.failure(IllegalStateException("Credential provider unavailable on this device/emulator."))
        } catch (e: CreateCredentialException) {
            Log.e(TAG, "Passkey creation failed", e)
            Result.failure(IllegalStateException(e.localizedMessage ?: "Unable to create passkey on this device."))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected passkey registration error", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithPasskey(
        activity: Activity,
        credentialManager: CredentialManager,
        phoneNumberHint: String?,
        deviceId: String
    ): Result<PasskeyLoginOutcome> {
        return try {
            val beginResult = functionsClient.beginPasskeyAuthentication(phoneNumberHint)
            val beginData = beginResult.getOrElse { return Result.failure(it) }
            if (beginData.optionsJson.isBlank() || beginData.challengeId.isBlank()) {
                return Result.failure(IllegalStateException("Passkey authentication challenge missing from server."))
            }

            val getPasskeyOption = GetPublicKeyCredentialOption(
                requestJson = beginData.optionsJson
            )
            val getRequest = GetCredentialRequest.Builder()
                .addCredentialOption(getPasskeyOption)
                .build()

            val response = credentialManager.getCredential(
                context = activity,
                request = getRequest
            )
            val credential = response.credential
            if (credential !is PublicKeyCredential) {
                return Result.failure(IllegalStateException("Expected PublicKeyCredential assertion from authenticator."))
            }

            val finishResult = functionsClient.finishPasskeyAuthentication(
                challengeId = beginData.challengeId,
                authenticationResponseJson = credential.authenticationResponseJson,
                deviceId = deviceId
            )
            val loginData = finishResult.getOrElse { return Result.failure(it) }
            auth.signInWithCustomToken(loginData.customToken).await()
            Result.success(PasskeyLoginOutcome(uid = loginData.uid, sessionId = loginData.sessionId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Passkey authentication cancelled by user", e)
            Result.failure(IllegalStateException("Passkey verification was cancelled."))
        } catch (e: NoCredentialException) {
            Result.failure(IllegalStateException("No enrolled passkey found on this device for Family Finance."))
        } catch (e: GetPublicKeyCredentialDomException) {
            Result.failure(IllegalStateException("WebAuthn assertion error: ${e.message ?: "Verify Digital Asset Links for ${BuildConfig.WEBAUTHN_RP_ID}"}"))
        } catch (e: GetCredentialException) {
            Result.failure(IllegalStateException(e.localizedMessage ?: "Passkey sign-in failed."))
        } catch (e: Exception) {
            Log.e(TAG, "Passkey sign-in failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "PasskeyService"
    }
}
