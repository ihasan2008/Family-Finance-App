package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.firebase.AppEnvironment
import com.example.core.firebase.AuthBackendException
import com.example.core.firebase.AuthFunctionsClient
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.model.AuthValidation
import com.example.core.remote.FirebaseFailure
import com.example.core.remote.classifyFirebaseError
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseIntegrationTest {

    @Test
    fun classifyFirebaseError_mapsPermissionDenied() {
        val ex = FirebaseFirestoreException(
            "Missing or insufficient permissions.",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val failure = classifyFirebaseError(ex)
        assertTrue(failure is FirebaseFailure.PermissionDenied)
    }

    @Test
    fun classifyFirebaseError_mapsUnauthenticated() {
        val ex = FirebaseFirestoreException(
            "User not authenticated.",
            FirebaseFirestoreException.Code.UNAUTHENTICATED
        )
        val failure = classifyFirebaseError(ex)
        assertTrue(failure is FirebaseFailure.Unauthenticated)
    }

    @Test
    fun classifyFirebaseError_mapsUnavailableAndQuotaExceeded() {
        val unavailable = FirebaseFirestoreException(
            "Network offline",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )
        val quota = FirebaseFirestoreException(
            "Quota exceeded",
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
        )
        assertTrue(classifyFirebaseError(unavailable) is FirebaseFailure.NetworkUnavailable)
        assertTrue(classifyFirebaseError(quota) is FirebaseFailure.QuotaExceeded)
    }

    @Test
    fun debugBuildEnvironment_isDevelopment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = FirebaseServiceRegistry.inspectConfiguration(context)
        assertEquals(AppEnvironment.DEVELOPMENT, snapshot.environment)
        assertTrue(snapshot.firestoreDatabaseId.isNotBlank())
        assertTrue(snapshot.persistentCacheEnabled)
    }

    @Test
    fun authValidation_normalizesE164AndEvaluatesPasswordStrength() {
        assertEquals("+14155550199", AuthValidation.normalizeE164Phone("+1 (415) 555-0199"))
        assertTrue(AuthValidation.isValidE164Phone("+8801712345678"))
        assertFalse(AuthValidation.isValidE164Phone("01712345678"))

        val weak = AuthValidation.evaluatePasswordStrength("pass123")
        assertFalse(weak.isAcceptable)

        val strong = AuthValidation.evaluatePasswordStrength("Family#2026Secure")
        assertTrue(strong.isAcceptable)
        assertEquals(4, strong.score)
    }

    @Test
    fun authFunctionsClient_failsFastWhenBackendUrlMissing() = runTest {
        val client = AuthFunctionsClient(baseUrlProvider = { "" })
        assertFalse(client.isConfigured)
        val result = client.loginWithPhonePassword("+14155550199", "Family#2026", "dev_01")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as? AuthBackendException
        assertEquals("BACKEND_URL_NOT_CONFIGURED", err?.code)
    }
}
