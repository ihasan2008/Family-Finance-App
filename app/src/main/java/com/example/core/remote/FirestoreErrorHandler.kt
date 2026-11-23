package com.example.core.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

sealed interface FirebaseFailure {
    val userMessage: String

    data class PermissionDenied(override val userMessage: String) : FirebaseFailure
    data class Unauthenticated(override val userMessage: String) : FirebaseFailure
    data class NetworkUnavailable(override val userMessage: String) : FirebaseFailure
    data class QuotaExceeded(override val userMessage: String) : FirebaseFailure
    data class AuthError(override val userMessage: String) : FirebaseFailure
    data class Unexpected(override val userMessage: String) : FirebaseFailure
}

fun classifyFirebaseError(exception: Throwable): FirebaseFailure {
    val causes = generateSequence(exception) { it.cause }
    val firestoreError = causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
    if (firestoreError != null) {
        return when (firestoreError.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                FirebaseFailure.PermissionDenied("Access denied by Firestore security rules for this family or user.")
            FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                FirebaseFailure.Unauthenticated("Please sign in with Google to synchronize cloud data.")
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                FirebaseFailure.NetworkUnavailable("Cloud Firestore is temporarily unreachable. Local Room cache remains active.")
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                FirebaseFailure.QuotaExceeded("Firestore daily free-tier quota exceeded. Changes are cached locally until quota resets.")
            else ->
                FirebaseFailure.Unexpected(firestoreError.localizedMessage ?: "Cloud sync error occurred.")
        }
    }

    val authError = causes.filterIsInstance<FirebaseAuthException>().firstOrNull()
    if (authError != null) {
        return FirebaseFailure.AuthError(authError.localizedMessage ?: "Authentication session error.")
    }

    return FirebaseFailure.Unexpected(exception.localizedMessage ?: "Unexpected sync error.")
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}
