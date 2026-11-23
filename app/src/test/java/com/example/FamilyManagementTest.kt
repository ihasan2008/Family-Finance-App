package com.example

import com.example.core.firebase.AuthBackendException
import com.example.core.firebase.AuthFunctionsClient
import com.example.core.local.LocalFamilyEntity
import com.example.core.model.FamilyRole
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.InvitationStatus
import com.example.core.model.JoinRequestStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyManagementTest {

    @Test
    fun generateRandomFamilyId_producesUniqueFormattedIds() {
        val id1 = FamilySecurityHelper.generateRandomFamilyId()
        val id2 = FamilySecurityHelper.generateRandomFamilyId()

        assertTrue(id1.matches(Regex("^[A-Z0-9]{12}$")))
        assertTrue(id2.matches(Regex("^[A-Z0-9]{12}$")))
        assertNotEquals(id1, id2)
        assertTrue(FamilySecurityHelper.isValidFamilyId12("ab-12 cd 34 ef 56"))
        assertEquals("AB12CD34EF56", FamilySecurityHelper.normalizeFamilyIdInput(" ab-12 cd 34 ef 56 "))
    }

    @Test
    fun invitationTokenGenerationAndHashing_normalizesCaseAndWhitespace() {
        val generated = FamilySecurityHelper.generateInvitationToken()
        assertTrue(generated.token.startsWith("FFINV-"))
        assertTrue(generated.tokenHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(generated.tokenPreview.startsWith("FFINV-"))

        val lowercaseWithSpaces = "  ${generated.token.lowercase()}  "
        assertEquals(
            generated.tokenHash,
            FamilySecurityHelper.hashInvitationToken(lowercaseWithSpaces)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun hashInvitationToken_rejectsShortToken() {
        FamilySecurityHelper.hashInvitationToken("fam_1234")
    }

    @Test
    fun familyEnums_mapLegacyAndCanonicalValues() {
        assertEquals(FamilyRole.ADMIN, FamilyRole.fromRaw("OWNER"))
        assertEquals(FamilyRole.ADMIN, FamilyRole.fromRaw("admin"))
        assertEquals(FamilyRole.MEMBER, FamilyRole.fromRaw("VIEWER"))
        assertEquals(FamilyRole.MEMBER, FamilyRole.fromRaw("MEMBER"))
        assertEquals(FamilyRole.MEMBER, FamilyRole.fromRaw(null))

        assertEquals(InvitationStatus.REVOKED, InvitationStatus.fromRaw("revoked"))
        assertEquals(InvitationStatus.USED, InvitationStatus.fromRaw("USED"))
        assertEquals(JoinRequestStatus.APPROVED, JoinRequestStatus.fromRaw("APPROVED"))
        assertEquals(
            JoinRequestStatus.PENDING_APPROVAL,
            JoinRequestStatus.fromRaw("unknown")
        )
    }

    @Test
    fun localFamilyEntity_distinguishesAdminsAndMembers() {
        val family = LocalFamilyEntity(
            familyId = "FAM012345678",
            name = "Khan Household",
            ownerId = "uid_owner",
            createdByUserId = "uid_creator",
            currencyCode = "USD",
            memberUidsCsv = "uid_owner,uid_co_admin,uid_member",
            adminUidsCsv = "uid_owner,uid_co_admin"
        )

        assertTrue(family.isAdmin("uid_owner"))
        assertTrue(family.isAdmin("uid_co_admin"))
        assertFalse(family.isAdmin("uid_member"))
        assertEquals(listOf("uid_owner", "uid_co_admin", "uid_member"), family.memberUids)
        assertEquals(listOf("uid_owner", "uid_co_admin"), family.adminUids)
    }

    @Test
    fun familyFunctionsClient_failsFastWhenBackendUrlMissing() = runTest {
        val client = AuthFunctionsClient(baseUrlProvider = { "" })
        val createResult = client.createFamily(
            name = "Test Family",
            currencyCode = "USD",
            description = null,
            displayName = "Amina",
            familyPassword = "FamPass!2026"
        )
        assertTrue(createResult.isFailure)
        val err = createResult.exceptionOrNull() as? AuthBackendException
        assertEquals("BACKEND_URL_NOT_CONFIGURED", err?.code)

        val joinResult = client.requestJoinWithInvitation(
            invitationToken = "FFINV-AAAAAA-BBBBBB-CCCCCC",
            relationLabel = "Sibling",
            displayName = "Tariq"
        )
        assertTrue(joinResult.isFailure)
        assertEquals(
            "BACKEND_URL_NOT_CONFIGURED",
            (joinResult.exceptionOrNull() as? AuthBackendException)?.code
        )
    }
}
