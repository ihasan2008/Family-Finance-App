package com.example.core.firebase

import android.os.Build
import com.example.BuildConfig
import com.example.core.model.ApiErrorResponse
import com.example.core.model.BeginPasskeyAuthResponse
import com.example.core.model.BeginPasskeyRegResponse
import com.example.core.model.ChangeMobileResponse
import com.example.core.model.CreateFamilyResponse
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.CustomTokenLoginResponse
import com.example.core.model.DecisionActionResponse
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.FinishPasskeyRegResponse
import com.example.core.model.OtpGuardResponse
import com.example.core.model.RecoverFamiliesResponse
import com.example.core.model.RegisterAccountResponse
import com.example.core.model.RequestJoinResponse
import com.example.core.model.RevokeSessionsResponse
import com.example.core.model.SimpleFamilyActionResponse
import com.example.core.model.VerifyFamilyAdminResponse
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AuthBackendException(
    val code: String,
    override val message: String,
    val retryAfterSeconds: Int? = null,
    val uid: String? = null
) : Exception(message)

/**
 * Trusted HTTPS client communicating with Family Finance Cloud Functions (functions/src/index.ts).
 * Attaches Firebase App Check token and Firebase Auth ID token headers. Never simulates auth responses.
 */
class AuthFunctionsClient(
    private val auth: FirebaseAuth? = null,
    private val baseUrlProvider: () -> String = { BuildConfig.AUTH_FUNCTIONS_BASE_URL },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val resolvedAuth: FirebaseAuth
        get() = auth ?: FirebaseServiceRegistry.auth
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val isConfigured: Boolean
        get() = baseUrlProvider().trim().isNotBlank()

    fun currentDeviceLabel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { "Android Device" }
            .take(80)
    }

    suspend fun requestOtpGuard(phoneNumber: String, deviceId: String): Result<OtpGuardResponse> {
        return postJson(
            route = "requestOtpGuard",
            payload = buildJsonObject {
                put("phoneNumber", JsonPrimitive(phoneNumber))
                put("deviceId", JsonPrimitive(deviceId))
            },
            requireBearerToken = false
        ) { raw -> json.decodeFromString<OtpGuardResponse>(raw) }
    }

    suspend fun registerMemberPasswordless(
        phoneNumber: String,
        displayName: String,
        dateOfBirth: String,
        deviceId: String,
        sessionId: String
    ): Result<RegisterAccountResponse> {
        return postJson(
            route = "registerMemberPasswordless",
            payload = buildJsonObject {
                put("phoneNumber", JsonPrimitive(phoneNumber))
                put("displayName", JsonPrimitive(displayName))
                put("dateOfBirth", JsonPrimitive(dateOfBirth))
                put("deviceId", JsonPrimitive(deviceId))
                put("deviceLabel", JsonPrimitive(currentDeviceLabel()))
                put("sessionId", JsonPrimitive(sessionId))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RegisterAccountResponse>(raw) }
    }

    suspend fun changeVerifiedMobileNumber(
        newPhoneNumber: String,
        newPhoneIdToken: String,
        revokeOtherSessions: Boolean,
        keepSessionId: String?
    ): Result<ChangeMobileResponse> {
        return postJson(
            route = "changeVerifiedMobileNumber",
            payload = buildJsonObject {
                put("newPhoneNumber", JsonPrimitive(newPhoneNumber))
                put("newPhoneIdToken", JsonPrimitive(newPhoneIdToken))
                put("revokeOtherSessions", JsonPrimitive(revokeOtherSessions))
                if (!keepSessionId.isNullOrBlank()) {
                    put("keepSessionId", JsonPrimitive(keepSessionId))
                }
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<ChangeMobileResponse>(raw) }
    }

    suspend fun verifyFamilyAdminLogin(
        familyId: String,
        familyPassword: String
    ): Result<VerifyFamilyAdminResponse> {
        return postJson(
            route = "verifyFamilyAdminLogin",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(FamilySecurityHelper.normalizeFamilyIdInput(familyId)))
                put("familyPassword", JsonPrimitive(familyPassword))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<VerifyFamilyAdminResponse>(raw) }
    }

    suspend fun registerWithPhonePassword(
        phoneNumber: String,
        password: String,
        displayName: String,
        deviceId: String,
        sessionId: String
    ): Result<RegisterAccountResponse> {
        return postJson(
            route = "registerWithPhonePassword",
            payload = buildJsonObject {
                put("phoneNumber", JsonPrimitive(phoneNumber))
                put("password", JsonPrimitive(password))
                put("displayName", JsonPrimitive(displayName))
                put("deviceId", JsonPrimitive(deviceId))
                put("deviceLabel", JsonPrimitive(currentDeviceLabel()))
                put("sessionId", JsonPrimitive(sessionId))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RegisterAccountResponse>(raw) }
    }

    suspend fun loginWithPhonePassword(
        phoneNumber: String,
        password: String,
        deviceId: String,
        otpIdToken: String? = null
    ): Result<CustomTokenLoginResponse> {
        return postJson(
            route = "loginWithPhonePassword",
            payload = buildJsonObject {
                put("phoneNumber", JsonPrimitive(phoneNumber))
                put("password", JsonPrimitive(password))
                put("deviceId", JsonPrimitive(deviceId))
                put("deviceLabel", JsonPrimitive(currentDeviceLabel()))
                if (!otpIdToken.isNullOrBlank()) {
                    put("otpIdToken", JsonPrimitive(otpIdToken))
                }
            },
            requireBearerToken = false
        ) { raw -> json.decodeFromString<CustomTokenLoginResponse>(raw) }
    }

    suspend fun resetPasswordWithOtp(
        phoneNumber: String,
        newPassword: String
    ): Result<CustomTokenLoginResponse> {
        return postJson(
            route = "resetPasswordWithOtp",
            payload = buildJsonObject {
                put("phoneNumber", JsonPrimitive(phoneNumber))
                put("newPassword", JsonPrimitive(newPassword))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<CustomTokenLoginResponse>(raw) }
    }

    suspend fun beginPasskeyRegistration(): Result<BeginPasskeyRegResponse> {
        return postJson(
            route = "beginPasskeyRegistration",
            payload = buildJsonObject {},
            requireBearerToken = true
        ) { raw -> json.decodeFromString<BeginPasskeyRegResponse>(raw) }
    }

    suspend fun finishPasskeyRegistration(
        registrationResponseJson: String,
        label: String
    ): Result<FinishPasskeyRegResponse> {
        return postJson(
            route = "finishPasskeyRegistration",
            payload = buildJsonObject {
                put("registrationResponseJson", JsonPrimitive(registrationResponseJson))
                put("label", JsonPrimitive(label))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<FinishPasskeyRegResponse>(raw) }
    }

    suspend fun beginPasskeyAuthentication(phoneNumberHint: String?): Result<BeginPasskeyAuthResponse> {
        return postJson(
            route = "beginPasskeyAuthentication",
            payload = buildJsonObject {
                if (!phoneNumberHint.isNullOrBlank()) {
                    put("phoneNumber", JsonPrimitive(phoneNumberHint))
                }
            },
            requireBearerToken = false
        ) { raw -> json.decodeFromString<BeginPasskeyAuthResponse>(raw) }
    }

    suspend fun finishPasskeyAuthentication(
        challengeId: String,
        authenticationResponseJson: String,
        deviceId: String
    ): Result<CustomTokenLoginResponse> {
        return postJson(
            route = "finishPasskeyAuthentication",
            payload = buildJsonObject {
                put("challengeId", JsonPrimitive(challengeId))
                put("authenticationResponseJson", JsonPrimitive(authenticationResponseJson))
                put("deviceId", JsonPrimitive(deviceId))
                put("deviceLabel", JsonPrimitive(currentDeviceLabel()))
            },
            requireBearerToken = false
        ) { raw -> json.decodeFromString<CustomTokenLoginResponse>(raw) }
    }

    suspend fun revokeAllSessions(keepSessionId: String?): Result<RevokeSessionsResponse> {
        return postJson(
            route = "revokeAllSessions",
            payload = buildJsonObject {
                if (!keepSessionId.isNullOrBlank()) {
                    put("keepSessionId", JsonPrimitive(keepSessionId))
                }
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RevokeSessionsResponse>(raw) }
    }

    suspend fun createFamily(
        name: String,
        currencyCode: String,
        description: String?,
        displayName: String,
        familyPassword: String = ""
    ): Result<CreateFamilyResponse> {
        return postJson(
            route = "createFamily",
            payload = buildJsonObject {
                put("name", JsonPrimitive(name))
                put("currencyCode", JsonPrimitive(currencyCode))
                if (!description.isNullOrBlank()) {
                    put("description", JsonPrimitive(description))
                }
                put("displayName", JsonPrimitive(displayName))
                if (familyPassword.isNotBlank()) {
                    put("familyPassword", JsonPrimitive(familyPassword))
                }
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<CreateFamilyResponse>(raw) }
    }

    suspend fun createFamilyInvitation(
        familyId: String,
        targetRole: String,
        inviteeHint: String?,
        expiresInHours: Int,
        maxUses: Int
    ): Result<CreateInvitationResponse> {
        return postJson(
            route = "createFamilyInvitation",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("targetRole", JsonPrimitive(targetRole))
                if (!inviteeHint.isNullOrBlank()) {
                    put("inviteeHint", JsonPrimitive(inviteeHint))
                }
                put("expiresInHours", JsonPrimitive(expiresInHours))
                put("maxUses", JsonPrimitive(maxUses))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<CreateInvitationResponse>(raw) }
    }

    suspend fun revokeFamilyInvitation(
        familyId: String,
        invitationId: String
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "revokeFamilyInvitation",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("invitationId", JsonPrimitive(invitationId))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun requestJoinWithInvitation(
        invitationToken: String,
        relationLabel: String,
        displayName: String
    ): Result<RequestJoinResponse> {
        return postJson(
            route = "requestJoinWithInvitation",
            payload = buildJsonObject {
                put("invitationToken", JsonPrimitive(invitationToken.trim().uppercase()))
                put("relationLabel", JsonPrimitive(relationLabel.trim()))
                put("displayName", JsonPrimitive(displayName.trim()))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RequestJoinResponse>(raw) }
    }

    suspend fun requestJoinByFamilyPassword(
        familyId: String,
        familyPassword: String,
        relationLabel: String,
        displayName: String
    ): Result<RequestJoinResponse> {
        return postJson(
            route = "requestJoinByFamilyPassword",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(FamilySecurityHelper.normalizeFamilyIdInput(familyId)))
                put("familyPassword", JsonPrimitive(familyPassword))
                put("relationLabel", JsonPrimitive(relationLabel.trim()))
                put("displayName", JsonPrimitive(displayName.trim()))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RequestJoinResponse>(raw) }
    }

    suspend fun reviewJoinRequest(
        familyId: String,
        requesterUid: String,
        approve: Boolean
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "reviewJoinRequest",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("requesterUid", JsonPrimitive(requesterUid))
                put("approve", JsonPrimitive(approve))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun updateMemberRole(
        familyId: String,
        targetUid: String,
        role: String
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "updateMemberRole",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("targetUid", JsonPrimitive(targetUid))
                put("role", JsonPrimitive(role))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun removeFamilyMember(
        familyId: String,
        targetUid: String
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "removeFamilyMember",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("targetUid", JsonPrimitive(targetUid))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun setMemberSuspension(
        familyId: String,
        targetUid: String,
        suspend: Boolean
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "setMemberSuspension",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("targetUid", JsonPrimitive(targetUid))
                put("suspend", JsonPrimitive(suspend))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun changeFamilyPassword(
        familyId: String,
        currentFamilyPassword: String,
        newFamilyPassword: String
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "changeFamilyPassword",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("currentFamilyPassword", JsonPrimitive(currentFamilyPassword))
                put("newFamilyPassword", JsonPrimitive(newFamilyPassword))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun transferFamilyOwnership(
        familyId: String,
        newOwnerUid: String,
        familyPassword: String = ""
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "transferFamilyOwnership",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("newOwnerUid", JsonPrimitive(newOwnerUid))
                if (familyPassword.isNotBlank()) {
                    put("familyPassword", JsonPrimitive(familyPassword))
                }
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun updateFamilySettings(
        familyId: String,
        name: String,
        currencyCode: String,
        description: String?,
        memberChatEnabled: Boolean,
        membersCanAddExpenses: Boolean,
        budgetStartDay: Int,
        chatRetentionDays: Int = 90
    ): Result<SimpleFamilyActionResponse> {
        return postJson(
            route = "updateFamilySettings",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("name", JsonPrimitive(name))
                put("currencyCode", JsonPrimitive(currencyCode))
                if (!description.isNullOrBlank()) {
                    put("description", JsonPrimitive(description))
                }
                put("memberChatEnabled", JsonPrimitive(memberChatEnabled))
                put("membersCanAddExpenses", JsonPrimitive(membersCanAddExpenses))
                put("budgetStartDay", JsonPrimitive(budgetStartDay))
                put("chatRetentionDays", JsonPrimitive(chatRetentionDays))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<SimpleFamilyActionResponse>(raw) }
    }

    suspend fun recoverAdminFamilies(): Result<RecoverFamiliesResponse> {
        return postJson(
            route = "recoverAdminFamilies",
            payload = buildJsonObject {},
            requireBearerToken = true
        ) { raw -> json.decodeFromString<RecoverFamiliesResponse>(raw) }
    }

    suspend fun configureApprovalRules(
        familyId: String,
        ruleMode: String,
        minApprovalPercent: Int,
        minQuorumCount: Int,
        defaultDeadlineHours: Int,
        allowCreatorVote: Boolean,
        allowVoteChangeBeforeDeadline: Boolean,
        eligibleVoterRule: String,
        specificEligibleUids: List<String>,
        requiredCategories: List<String>,
        largeExpenseThresholdMinor: Long
    ): Result<DecisionActionResponse> {
        return postJson(
            route = "decisions/configureRules",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("ruleMode", JsonPrimitive(ruleMode))
                put("minApprovalPercent", JsonPrimitive(minApprovalPercent))
                put("minQuorumCount", JsonPrimitive(minQuorumCount))
                put("defaultDeadlineHours", JsonPrimitive(defaultDeadlineHours))
                put("allowCreatorVote", JsonPrimitive(allowCreatorVote))
                put("allowVoteChangeBeforeDeadline", JsonPrimitive(allowVoteChangeBeforeDeadline))
                put("eligibleVoterRule", JsonPrimitive(eligibleVoterRule))
                put("specificEligibleUids", JsonArray(specificEligibleUids.map { JsonPrimitive(it) }))
                put("requiredCategories", JsonArray(requiredCategories.map { JsonPrimitive(it) }))
                put("largeExpenseThresholdMinor", JsonPrimitive(largeExpenseThresholdMinor))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<DecisionActionResponse>(raw) }
    }

    suspend fun submitProposalVoteRemote(
        familyId: String,
        proposalId: String,
        choice: String,
        comment: String?
    ): Result<DecisionActionResponse> {
        return postJson(
            route = "decisions/submitVote",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("proposalId", JsonPrimitive(proposalId))
                put("choice", JsonPrimitive(choice))
                if (!comment.isNullOrBlank()) {
                    put("comment", JsonPrimitive(comment))
                }
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<DecisionActionResponse>(raw) }
    }

    suspend fun cancelProposalRemote(
        familyId: String,
        proposalId: String,
        cancelReason: String
    ): Result<DecisionActionResponse> {
        return postJson(
            route = "decisions/cancelProposal",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("proposalId", JsonPrimitive(proposalId))
                put("cancelReason", JsonPrimitive(cancelReason))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<DecisionActionResponse>(raw) }
    }

    suspend fun executeProposalRemote(
        familyId: String,
        proposalId: String
    ): Result<DecisionActionResponse> {
        return postJson(
            route = "decisions/executeProposal",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("proposalId", JsonPrimitive(proposalId))
            },
            requireBearerToken = true
        ) { raw -> json.decodeFromString<DecisionActionResponse>(raw) }
    }

    private suspend fun <T> postJson(
        route: String,
        payload: JsonObject,
        requireBearerToken: Boolean,
        decode: (String) -> T
    ): Result<T> {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(
                AuthBackendException(
                    code = "BACKEND_URL_NOT_CONFIGURED",
                    message = "Cloud Functions Auth backend URL (AUTH_FUNCTIONS_BASE_URL) is not configured yet. Deploy /functions and set AUTH_FUNCTIONS_BASE_URL in gradle.properties."
                )
            )
        }

        return try {
            val idToken = if (requireBearerToken) {
                val user = resolvedAuth.currentUser
                    ?: return Result.failure(
                        AuthBackendException(
                            code = "UNAUTHENTICATED",
                            message = "Verified Firebase session required before calling $route."
                        )
                    )
                user.getIdToken(false).await().token
                    ?: return Result.failure(
                        AuthBackendException(
                            code = "UNAUTHENTICATED",
                            message = "Unable to retrieve Firebase ID token."
                        )
                    )
            } else {
                null
            }

            val appCheckToken = runCatching {
                Firebase.appCheck.getAppCheckToken(false).await().token
            }.getOrNull()

            val bodyString = payload.toString()
            val requestBuilder = Request.Builder()
                .url("$baseUrl/$route")
                .post(bodyString.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")

            if (!idToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $idToken")
            }
            if (!appCheckToken.isNullOrBlank()) {
                requestBuilder.header("X-Firebase-AppCheck", appCheckToken)
            }

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(requestBuilder.build()).execute()
            }
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(decode(responseBody))
            } else {
                val parsedErr = runCatching { json.decodeFromString<ApiErrorResponse>(responseBody) }
                    .getOrNull()
                Result.failure(
                    AuthBackendException(
                        code = parsedErr?.code ?: "HTTP_${response.code}",
                        message = parsedErr?.message ?: "Server returned HTTP ${response.code}",
                        retryAfterSeconds = parsedErr?.retryAfterSeconds,
                        uid = parsedErr?.uid
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                AuthBackendException(
                    code = "NETWORK_ERROR",
                    message = e.localizedMessage ?: "Unable to reach trusted auth server."
                )
            )
        }
    }
}
