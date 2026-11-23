package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.firebase.AuthBackendException
import com.example.core.firebase.AuthFunctionsClient
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.LocalUserProfileEntity
import com.example.core.local.abstraction.LocalEntityCipherMapper
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.AuthValidation
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Prompt13SecurityAndAuthTest {

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FamilyFinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SqliteRoomDatabaseStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun familySecurityHelper_personalWorkspaceAndPhoneMemberId() {
        val familyId = FamilySecurityHelper.generateRandomFamilyId()
        assertTrue(familyId.matches(Regex("^[A-Z0-9]{12}$")))
        assertTrue(FamilySecurityHelper.isValidFamilyId12(familyId))
        assertFalse(FamilySecurityHelper.isPersonalWorkspaceId(familyId))

        val personalId = FamilySecurityHelper.personalWorkspaceId("uid_alpha_99")
        assertEquals("personal_uid_alpha_99", personalId)
        assertTrue(FamilySecurityHelper.isPersonalWorkspaceId(personalId))

        val memberId = FamilySecurityHelper.resolveMemberIdFromPhone("+880 1711-223344")
        assertEquals("+8801711223344", memberId)
    }

    @Test
    fun authValidation_dobCalendarRulesPhoneMaskAndFamilyPasswordStrength() {
        assertEquals("2000-02-29", AuthValidation.validateDateOfBirthIso("2000-02-29"))
        assertEquals("1995-05-10", AuthValidation.validateDateOfBirthIso(" 1995-05-10 "))
        assertNull(AuthValidation.validateDateOfBirthIso("2025-02-29"))
        assertNull(AuthValidation.validateDateOfBirthIso("2099-01-01"))
        assertNull(AuthValidation.validateDateOfBirthIso("1899-12-31"))
        assertNull(AuthValidation.validateDateOfBirthIso("1995/05/10"))

        val masked = AuthValidation.maskPhoneNumber("+8801711225678")
        assertTrue(masked.contains("••••"))
        assertTrue(masked.endsWith("5678"))

        assertFalse(AuthValidation.evaluateFamilyPasswordStrength("weak").isAcceptable)
        assertTrue(AuthValidation.evaluateFamilyPasswordStrength("StrongFam!99").isAcceptable)
    }

    @Test
    fun cipherMapper_encryptsPrivateDateOfBirthAtRest() {
        val mapper = LocalEntityCipherMapper()
        val profile = LocalUserProfileEntity(
            userId = "uid_alice",
            displayName = "Alice Rahman",
            email = "alice@example.com",
            phoneNumber = "+8801711225678",
            memberId = "+8801711225678",
            maskedPhone = "+88017••••5678",
            dateOfBirth = "1994-11-21",
            passwordConfigured = false
        )

        val encrypted = mapper.encrypt(profile)
        assertTrue(encrypted.dateOfBirth?.startsWith("enc1:") == true)
        assertFalse(encrypted.dateOfBirth == "1994-11-21")

        val decrypted = mapper.decrypt(encrypted)
        assertEquals("1994-11-21", decrypted.dateOfBirth)
        assertFalse(decrypted.passwordConfigured)
    }

    @Test
    fun roomWorkspaceIsolation_andSuspensionGating() = runTest {
        val personalId = FamilySecurityHelper.personalWorkspaceId("uid_alice")
        val sharedFamilyId = "ABC123DEF456"

        val personalTx = LocalTransactionEntity(
            recordId = "tx_personal_1",
            familyId = personalId,
            createdByUserId = "uid_alice",
            amount = 500.0,
            currency = "BDT",
            category = "Personal Gift",
            description = "Private personal item",
            transactionType = TransactionType.EXPENSE.name,
            idempotencyKey = "idem_personal_1"
        )
        val sharedTx = LocalTransactionEntity(
            recordId = "tx_shared_1",
            familyId = sharedFamilyId,
            createdByUserId = "uid_alice",
            amount = 1200.0,
            currency = "BDT",
            category = "Groceries",
            description = "Shared household basket",
            transactionType = TransactionType.INCOME.name,
            idempotencyKey = "idem_shared_1"
        )

        store.saveTransactionIdempotent(
            personalTx,
            LocalAuditLogEntity(
                auditId = "aud_p1",
                familyId = personalId,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = "tx_personal_1",
                action = AuditAction.CREATE.name,
                summary = "Personal item"
            )
        )
        store.saveTransactionIdempotent(
            sharedTx,
            LocalAuditLogEntity(
                auditId = "aud_s1",
                familyId = sharedFamilyId,
                actorUserId = "uid_alice",
                entityCollection = "transactions",
                entityId = "tx_shared_1",
                action = AuditAction.CREATE.name,
                summary = "Shared item"
            )
        )

        val personalRows = store.observeTransactions(personalId).first()
        val sharedRows = store.observeTransactions(sharedFamilyId).first()
        assertEquals(listOf("tx_personal_1"), personalRows.map { it.recordId })
        assertEquals(listOf("tx_shared_1"), sharedRows.map { it.recordId })

        val familyWithSuspension = LocalFamilyEntity(
            familyId = sharedFamilyId,
            name = "Rahman Household",
            ownerId = "uid_alice",
            currencyCode = "BDT",
            memberUidsCsv = "uid_alice,uid_bob",
            adminUidsCsv = "uid_alice",
            suspendedUidsCsv = "uid_bob"
        )
        assertTrue(familyWithSuspension.isAdmin("uid_alice"))
        assertFalse(familyWithSuspension.isSuspended("uid_alice"))
        assertTrue(familyWithSuspension.isSuspended("uid_bob"))
        assertFalse(familyWithSuspension.isAdmin("uid_bob"))
    }

    @Test
    fun authFunctionsClient_failsFastOnPrompt13RoutesWhenUrlMissing() = runTest {
        val client = AuthFunctionsClient(baseUrlProvider = { "" })

        val calls = listOf(
            client.registerMemberPasswordless("+8801711223344", "Amina", "1996-03-14", "dev1", "sess1"),
            client.verifyFamilyAdminLogin("ABC123DEF456", "FamPass!2026"),
            client.requestJoinByFamilyPassword("ABC123DEF456", "FamPass!2026", "Spouse", "Tariq"),
            client.setMemberSuspension("ABC123DEF456", "uid_bob", true),
            client.changeFamilyPassword("ABC123DEF456", "OldPass!1", "NewPass!2"),
            client.changeVerifiedMobileNumber("+8801811223344", "tok", false, "sess1")
        )

        calls.forEach { result ->
            assertTrue(result.isFailure)
            val err = result.exceptionOrNull() as? AuthBackendException
            assertEquals("BACKEND_URL_NOT_CONFIGURED", err?.code)
        }
    }
}
