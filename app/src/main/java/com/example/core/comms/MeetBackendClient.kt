package com.example.core.comms

import com.example.BuildConfig
import com.example.core.firebase.AuthBackendException
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.model.ApiErrorResponse
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

/**
 * Authenticated HTTP client calling trusted Cloud Functions `/meet` routes.
 * Never mints LiveKit JWTs client-side and never returns fake tokens when unconfigured.
 */
class MeetBackendClient(
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

    suspend fun mintMeetToken(
        familyId: String,
        meetingId: String,
        displayName: String
    ): Result<MeetTokenResponse> {
        return postJson(
            route = "meet/mintToken",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("meetingId", JsonPrimitive(meetingId))
                put("displayName", JsonPrimitive(displayName))
            }
        ) { raw -> json.decodeFromString<MeetTokenResponse>(raw) }
    }

    suspend fun muteParticipant(
        familyId: String,
        meetingId: String,
        targetUid: String,
        muted: Boolean,
        trackSid: String? = null
    ): Result<MeetModerationResponse> {
        return postJson(
            route = "meet/muteParticipant",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("meetingId", JsonPrimitive(meetingId))
                put("targetUid", JsonPrimitive(targetUid))
                put("muted", JsonPrimitive(muted))
                if (!trackSid.isNullOrBlank()) {
                    put("trackSid", JsonPrimitive(trackSid))
                }
            }
        ) { raw -> json.decodeFromString<MeetModerationResponse>(raw) }
    }

    suspend fun removeParticipant(
        familyId: String,
        meetingId: String,
        targetUid: String
    ): Result<MeetModerationResponse> {
        return postJson(
            route = "meet/removeParticipant",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("meetingId", JsonPrimitive(meetingId))
                put("targetUid", JsonPrimitive(targetUid))
            }
        ) { raw -> json.decodeFromString<MeetModerationResponse>(raw) }
    }

    suspend fun endMeeting(
        familyId: String,
        meetingId: String
    ): Result<MeetModerationResponse> {
        return postJson(
            route = "meet/endMeeting",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("meetingId", JsonPrimitive(meetingId))
            }
        ) { raw -> json.decodeFromString<MeetModerationResponse>(raw) }
    }

    suspend fun dispatchPush(
        familyId: String,
        targetUids: List<String>,
        title: String,
        body: String,
        notificationType: String,
        deepLinkModule: String = "FAMILY_MEET",
        relatedRecordId: String = ""
    ): Result<DispatchPushResponse> {
        return postJson(
            route = "meet/dispatchPush",
            payload = buildJsonObject {
                put("familyId", JsonPrimitive(familyId))
                put("targetUids", JsonArray(targetUids.map { JsonPrimitive(it) }))
                put("title", JsonPrimitive(title.take(100)))
                put("body", JsonPrimitive(body.take(400)))
                put("notificationType", JsonPrimitive(notificationType.take(40)))
                put("deepLinkModule", JsonPrimitive(deepLinkModule.take(40)))
                put("relatedRecordId", JsonPrimitive(relatedRecordId.take(80)))
            }
        ) { raw -> json.decodeFromString<DispatchPushResponse>(raw) }
    }

    private suspend fun <T> postJson(
        route: String,
        payload: JsonObject,
        decode: (String) -> T
    ): Result<T> {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(
                AuthBackendException(
                    code = "BACKEND_URL_NOT_CONFIGURED",
                    message = "Cloud Functions backend URL (AUTH_FUNCTIONS_BASE_URL) is not configured for LiveKit tokens and push alerts."
                )
            )
        }

        return try {
            val user = resolvedAuth.currentUser
                ?: return Result.failure(
                    AuthBackendException(
                        code = "UNAUTHENTICATED",
                        message = "Authenticated Firebase session required before calling $route."
                    )
                )
            val idToken = user.getIdToken(false).await().token
                ?: return Result.failure(
                    AuthBackendException(
                        code = "UNAUTHENTICATED",
                        message = "Unable to acquire Firebase ID token."
                    )
                )
            val appCheckToken = runCatching {
                Firebase.appCheck.getAppCheckToken(false).await().token
            }.getOrNull()

            val requestBuilder = Request.Builder()
                .url("$baseUrl/$route")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $idToken")

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
                val parsed = runCatching { json.decodeFromString<ApiErrorResponse>(responseBody) }.getOrNull()
                Result.failure(
                    AuthBackendException(
                        code = parsed?.code ?: "HTTP_${response.code}",
                        message = parsed?.message ?: "Server returned HTTP ${response.code}",
                        retryAfterSeconds = parsed?.retryAfterSeconds,
                        uid = parsed?.uid
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                AuthBackendException(
                    code = "NETWORK_ERROR",
                    message = e.localizedMessage ?: "Unable to reach Family Meet backend."
                )
            )
        }
    }
}
