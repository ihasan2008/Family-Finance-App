package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalMemberCardEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.FamilyRole
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.FinancialModuleType
import com.example.core.model.LoanDirection
import com.example.core.model.MemberAccountEngine
import com.example.core.model.MemberCardDoc
import com.example.core.model.MemberLedgerApprovalStatus
import com.example.core.model.MemberLedgerDatePreset
import com.example.core.model.MemberLedgerFilter
import com.example.core.model.MemberLedgerItemType
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MemberSortOption
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecordStatus
import com.example.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Prompt14MemberCardAccountTest {

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
    fun memberCardIdGeneration_enforces16UppercaseAlphanumericAndUniqueness() {
        val seen = mutableSetOf<String>()
        repeat(250) {
            val id = FamilySecurityHelper.generateMemberCardId16()
            assertEquals("Card Member ID must be 16 chars", 16, id.length)
            assertTrue("Must match ^[A-Z0-9]{16}$: $id", FamilySecurityHelper.isValidMemberCardId16(id))
            assertTrue("Generated ID must be unique", seen.add(id))
        }
        assertFalse(FamilySecurityHelper.isValidMemberCardId16("SHORT123"))
        assertFalse(FamilySecurityHelper.isValidMemberCardId16("ABCDEFGHIJKLMN-1"))
        assertFalse(FamilySecurityHelper.isValidMemberCardId16("ABCDEFGHIJKLMNOPQ"))
    }

    @Test
    fun memberCardPayload_neverContainsPrivateMobileDobOrPassword() {
        val doc = MemberCardDoc(
            userId = "uid_rafiq",
            familyId = "FAM9876543210AB",
            cardMemberId = "AB12CD34EF56GH78",
            familyName = "Rahman Family",
            displayName = "Rafiq Rahman",
            photoUrl = "https://example.com/rafiq.jpg",
            role = FamilyRole.MEMBER.name,
            memberStatus = "ACTIVE",
            memberUids = listOf("uid_admin", "uid_rafiq"),
            adminUids = listOf("uid_admin")
        )
        val payload = doc.toCreateMap()
        val forbiddenKeys = setOf(
            "phoneNumber",
            "maskedPhone",
            "dateOfBirth",
            "dob",
            "password",
            "passwordHash",
            "passwordSalt"
        )
        forbiddenKeys.forEach { key ->
            assertFalse("Forbidden PII key '$key' must never be present in MemberCardDoc", payload.containsKey(key))
        }
        assertEquals("AB12CD34EF56GH78", payload["cardMemberId"])
    }

    @Test
    fun accountingRules_isolateLoansSavingsInvestmentsProfitLossAndContributions() {
        val famId = "FAM1234567890AB"
        val uid = "uid_farhana"
        val now = 1760000000000L
        val family = LocalFamilyEntity(
            familyId = famId,
            name = "Chowdhury Household",
            ownerId = "uid_admin",
            currencyCode = "BDT",
            memberUidsCsv = "uid_admin,$uid",
            adminUidsCsv = "uid_admin"
        )

        // Exact minor units (paisa for BDT):
        // Ordinary Income: 50,000.10 BDT = 5,000,010
        // Family Contribution: 10,000.20 BDT = 1,000,020
        // Ordinary Expense: 12,000.00 BDT = 1,200,000
        // Loan Borrowed (remaining): 8,000.00 BDT = 800,000 (NOT income!)
        // Loan Lent (remaining): 3,000.00 BDT = 300,000
        // Savings Balance: 5,000.00 BDT = 500,000 (NOT income!)
        // Investment #1: Principal 6,000.00 (600,000), Current Value 7,500.00 (750,000) -> Profit +1,500.00
        // Investment #2: Principal 4,000.00 (400,000), Current Value 3,500.00 (350,000) -> Loss -500.00
        val transactions = listOf(
            LocalTransactionEntity(
                recordId = "tx_inc_1",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 50000.10,
                amountMinor = 5_000_010L,
                currency = "BDT",
                category = "Salary",
                description = "Monthly Salary",
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = now,
                idempotencyKey = "k_inc_1"
            ),
            LocalTransactionEntity(
                recordId = "tx_contrib_1",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 10000.20,
                amountMinor = 1_000_020L,
                currency = "BDT",
                category = "Personal Contribution",
                description = "Contribution from Personal Workspace",
                transactionType = TransactionType.INCOME.name,
                paymentMethod = "Personal Contribution",
                occurredAtMillis = now + 1000L,
                idempotencyKey = "k_contrib_1"
            ),
            LocalTransactionEntity(
                recordId = "tx_exp_1",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 12000.00,
                amountMinor = 1_200_000L,
                currency = "BDT",
                category = "Groceries",
                description = "Monthly Bazaar",
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = now + 2000L,
                idempotencyKey = "k_exp_1"
            )
        )

        val loans = listOf(
            LocalLoanEntity(
                recordId = "loan_borrowed_1",
                familyId = famId,
                createdByUserId = uid,
                amount = 10000.0,
                amountMinor = 1_000_000L,
                remainingBalance = 8000.0,
                remainingBalanceMinor = 800_000L,
                currency = "BDT",
                category = "Personal",
                description = "Borrowed emergency loan",
                loanDirection = LoanDirection.BORROWED.name,
                counterparty = "Uncle Karim",
                idempotencyKey = "k_loan_b"
            ),
            LocalLoanEntity(
                recordId = "loan_lent_1",
                familyId = famId,
                createdByUserId = uid,
                amount = 3000.0,
                amountMinor = 300_000L,
                remainingBalance = 3000.0,
                remainingBalanceMinor = 300_000L,
                currency = "BDT",
                category = "Family",
                description = "Lent to cousin",
                loanDirection = LoanDirection.LENT.name,
                counterparty = "Cousin Tariq",
                idempotencyKey = "k_loan_l"
            )
        )

        val savings = listOf(
            LocalSavingsEntity(
                recordId = "sav_1",
                familyId = famId,
                createdByUserId = uid,
                amount = 5000.0,
                amountMinor = 500_000L,
                targetAmount = 20000.0,
                targetAmountMinor = 2_000_000L,
                currency = "BDT",
                category = "Emergency",
                description = "DPS Reserve",
                goalName = "Emergency DPS",
                idempotencyKey = "k_sav_1"
            )
        )

        val investments = listOf(
            LocalInvestmentEntity(
                recordId = "inv_1",
                familyId = famId,
                createdByUserId = uid,
                amount = 6000.0,
                amountMinor = 600_000L,
                currentValue = 7500.0,
                currentValueMinor = 750_000L,
                currency = "BDT",
                category = "Stocks",
                description = "DSE Bluechip",
                assetName = "Square Pharma",
                idempotencyKey = "k_inv_1"
            ),
            LocalInvestmentEntity(
                recordId = "inv_2",
                familyId = famId,
                createdByUserId = uid,
                amount = 4000.0,
                amountMinor = 400_000L,
                currentValue = 3500.0,
                currentValueMinor = 350_000L,
                currency = "BDT",
                category = "Mutual Fund",
                description = "Balanced Fund",
                assetName = "Shariah MF",
                idempotencyKey = "k_inv_2"
            )
        )

        val summary = MemberAccountEngine.calculateMemberSummary(
            family = family,
            memberUserId = uid,
            familyTotalBalanceMinor = 4_800_030L,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments
        )

        // Verify strict separation & exact minor units (no 0.1 + 0.2 floating point drift!)
        assertEquals(5_000_010L, summary.totalIncomeMinor)
        assertEquals(1_000_020L, summary.familyContributionsMinor)
        assertEquals(1_200_000L, summary.totalExpenseMinor)
        assertEquals(300_000L, summary.outstandingLoansGivenMinor)
        assertEquals(800_000L, summary.outstandingLoansReceivedMinor)
        assertEquals(500_000L, summary.savingsBalanceMinor)
        assertEquals(1_000_000L, summary.investmentPrincipalMinor)
        assertEquals(150_000L, summary.investmentProfitMinor)
        assertEquals(50_000L, summary.investmentLossMinor)
        assertEquals(100_000L, summary.investmentNetProfitLossMinor)

        // Available balance = 5,000,010 + 1,000,020 - 1,200,000 + 800,000 - 300,000 - 500,000 - 1,000,000 = 3,800,030
        assertEquals(3_800_030L, summary.availableBalanceMinor)
        assertEquals("৳38,000.30", MoneyCalculator.formatMinorUnits(summary.availableBalanceMinor, "BDT"))
    }

    @Test
    fun ledgerConstructionAndFiltering_includesAllNineFieldsAndSupportsCategoryAndDateFilters() {
        val famId = "FAM_LEDGER_01"
        val uid = "uid_nadia"
        val zone = ZoneId.systemDefault()
        val todayNoon = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val tenDaysAgo = LocalDate.now(zone).minusDays(10).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

        val family = LocalFamilyEntity(
            familyId = famId,
            name = "Haque Family",
            ownerId = uid,
            currencyCode = "BDT",
            memberUidsCsv = uid,
            adminUidsCsv = uid
        )
        val members = listOf(
            LocalFamilyMemberEntity(
                familyId = famId,
                userId = uid,
                displayName = "Nadia Haque",
                role = FamilyRole.ADMIN.name,
                cardMemberId = "ZXCVBNM123456789"
            )
        )
        val transactions = listOf(
            LocalTransactionEntity(
                recordId = "rec_inc_today",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 2500.0,
                amountMinor = 250_000L,
                currency = "BDT",
                category = "Freelancing",
                description = "Design Sprint",
                recordStatus = RecordStatus.ACTIVE.name,
                transactionType = TransactionType.INCOME.name,
                occurredAtMillis = todayNoon,
                idempotencyKey = "ref_inc_today"
            ),
            LocalTransactionEntity(
                recordId = "rec_exp_old",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 500.0,
                amountMinor = 50_000L,
                currency = "BDT",
                category = "Utilities",
                description = "Internet Bill",
                recordStatus = RecordStatus.PENDING_REVIEW.name,
                transactionType = TransactionType.EXPENSE.name,
                occurredAtMillis = tenDaysAgo,
                idempotencyKey = "ref_exp_old"
            ),
            LocalTransactionEntity(
                recordId = "rec_contrib",
                familyId = famId,
                createdByUserId = uid,
                memberId = uid,
                amount = 1500.0,
                amountMinor = 150_000L,
                currency = "BDT",
                category = "Personal Contribution",
                description = "Family Pool Deposit",
                recordStatus = RecordStatus.ACTIVE.name,
                transactionType = TransactionType.INCOME.name,
                paymentMethod = "Personal Contribution",
                occurredAtMillis = todayNoon - 1000L,
                idempotencyKey = "ref_contrib"
            )
        )
        val loans = listOf(
            LocalLoanEntity(
                recordId = "loan_01",
                familyId = famId,
                createdByUserId = uid,
                amount = 1000.0,
                amountMinor = 100_000L,
                remainingBalance = 500.0,
                remainingBalanceMinor = 50_000L,
                currency = "BDT",
                category = "Personal",
                description = "Loan principal",
                loanDirection = LoanDirection.LENT.name,
                counterparty = "Imran",
                startDateMillis = todayNoon - 2000L,
                idempotencyKey = "ref_loan_01"
            )
        )
        val events = listOf(
            LocalFinancialEventEntity(
                eventId = "ev_repay_01",
                familyId = famId,
                parentRecordId = "loan_01",
                moduleType = FinancialModuleType.LOANS.name,
                eventType = "REPAYMENT",
                actorUserId = uid,
                amountMinor = 50_000L,
                currency = "BDT",
                note = "First installment",
                eventDateMillis = todayNoon - 500L
            ),
            LocalFinancialEventEntity(
                eventId = "ev_sav_01",
                familyId = famId,
                parentRecordId = "sav_01",
                moduleType = FinancialModuleType.SAVINGS.name,
                eventType = "DEPOSIT",
                actorUserId = uid,
                amountMinor = 75_000L,
                currency = "BDT",
                note = "Monthly DPS deposit",
                eventDateMillis = todayNoon - 300L
            )
        )
        val savings = listOf(
            LocalSavingsEntity(
                recordId = "sav_01",
                familyId = famId,
                createdByUserId = uid,
                amount = 750.0,
                amountMinor = 75_000L,
                targetAmount = 5000.0,
                targetAmountMinor = 500_000L,
                currency = "BDT",
                category = "Goal",
                description = "Education fund",
                goalName = "Education DPS",
                idempotencyKey = "ref_sav_01"
            )
        )
        val investments = listOf(
            LocalInvestmentEntity(
                recordId = "inv_01",
                familyId = famId,
                createdByUserId = uid,
                amount = 2000.0,
                amountMinor = 200_000L,
                currentValue = 2300.0,
                currentValueMinor = 230_000L,
                currency = "BDT",
                category = "Bond",
                description = "Sanchaypatra",
                assetName = "Family Sanchaypatra",
                investedAtMillis = todayNoon - 4000L,
                idempotencyKey = "ref_inv_01"
            )
        )

        val ledger = MemberAccountEngine.buildMemberLedger(
            family = family,
            memberUserId = uid,
            members = members,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            financialEvents = events
        )

        // Income + Expense + Contribution + Loan + Repayment + Savings Deposit Event + Investment Principal = 7
        assertEquals(7, ledger.size)

        val oldExpense = ledger.first { it.transactionId == "rec_exp_old" }
        assertEquals(MemberLedgerApprovalStatus.PENDING_REVIEW, oldExpense.approvalStatus)
        assertEquals("ref_exp_old", oldExpense.referenceId)
        assertEquals("Nadia Haque", oldExpense.relatedMemberName)

        assertEquals(
            1,
            MemberAccountEngine.filterLedgerEntries(ledger, MemberLedgerFilter.INCOME, MemberLedgerDatePreset.ALL_TIME).size
        )
        assertEquals(
            1,
            MemberAccountEngine.filterLedgerEntries(ledger, MemberLedgerFilter.FAMILY_CONTRIBUTION, MemberLedgerDatePreset.ALL_TIME).size
        )
        assertEquals(
            2,
            MemberAccountEngine.filterLedgerEntries(ledger, MemberLedgerFilter.LOAN, MemberLedgerDatePreset.ALL_TIME).size
        )
        assertEquals(
            1,
            MemberAccountEngine.filterLedgerEntries(ledger, MemberLedgerFilter.SAVINGS, MemberLedgerDatePreset.ALL_TIME).size
        )
        assertEquals(
            1,
            MemberAccountEngine.filterLedgerEntries(ledger, MemberLedgerFilter.INVESTMENT, MemberLedgerDatePreset.ALL_TIME).size
        )

        // Date preset TODAY excludes the 10-day-old expense
        val todayEntries = MemberAccountEngine.filterLedgerEntries(
            entries = ledger,
            typeFilter = MemberLedgerFilter.ALL,
            datePreset = MemberLedgerDatePreset.TODAY
        )
        assertFalse(todayEntries.any { it.transactionId == "rec_exp_old" })
        assertEquals(6, todayEntries.size)

        // Search by Reference ID
        val refMatch = MemberAccountEngine.filterLedgerEntries(
            entries = ledger,
            typeFilter = MemberLedgerFilter.ALL,
            datePreset = MemberLedgerDatePreset.ALL_TIME,
            searchQuery = "ref_inv_01"
        )
        assertEquals(1, refMatch.size)
        assertEquals(MemberLedgerItemType.INVESTMENT, refMatch.first().type)
    }

    @Test
    fun privacyRedaction_respectsMemberFlagsForPeersAndAdminAuditSetting() {
        val fam = LocalFamilyEntity(
            familyId = "FAM_PRIVACY_01",
            name = "Privacy Family",
            ownerId = "uid_admin",
            currencyCode = "BDT",
            memberUidsCsv = "uid_admin,uid_owner,uid_peer",
            adminUidsCsv = "uid_admin"
        )
        val members = listOf(
            LocalFamilyMemberEntity(
                familyId = fam.familyId,
                userId = "uid_owner",
                displayName = "Owner Member",
                role = FamilyRole.MEMBER.name,
                cardMemberId = "1111222233334444"
            )
        )
        val hiddenAllCard = LocalMemberCardEntity(
            familyId = fam.familyId,
            userId = "uid_owner",
            cardMemberId = "1111222233334444",
            familyName = fam.name,
            displayName = "Owner Member",
            showAvailableBalance = false,
            showIncomeAndExpense = false,
            showFamilyContributions = false,
            showLoans = false,
            showSavings = false,
            showInvestments = false,
            showTransactionHistory = false,
            allowAdminDetailedAudit = false
        )
        val directoryForPeer = MemberAccountEngine.buildDirectoryCards(
            family = fam,
            members = members,
            memberCards = listOf(hiddenAllCard),
            transactions = emptyList(),
            loans = emptyList(),
            savings = emptyList(),
            investments = emptyList(),
            viewerUid = "uid_peer",
            viewerIsAdmin = false
        )
        val ownerCard = directoryForPeer.first()
        assertFalse(ownerCard.canViewerSeeAvailableBalance)

        // Peer sees redacted profile & empty history
        val peerProfile = MemberAccountEngine.buildMemberProfileView(
            card = ownerCard,
            viewerUid = "uid_peer",
            viewerIsAdmin = false,
            allLedgerEntries = emptyList()
        )
        assertFalse(peerProfile.canSeeBalance)
        assertFalse(peerProfile.canSeeHistory)

        // Owner always sees their own details
        val selfProfile = MemberAccountEngine.buildMemberProfileView(
            card = ownerCard,
            viewerUid = "uid_owner",
            viewerIsAdmin = false,
            allLedgerEntries = emptyList()
        )
        assertTrue(selfProfile.canSeeBalance)
        assertTrue(selfProfile.canSeeHistory)
        assertTrue(selfProfile.isViewerOwner)

        // Admin cannot bypass when allowAdminDetailedAudit == false; CAN audit when true
        val adminBlocked = MemberAccountEngine.buildMemberProfileView(
            card = ownerCard,
            viewerUid = "uid_admin",
            viewerIsAdmin = true,
            allLedgerEntries = emptyList()
        )
        assertFalse(adminBlocked.canViewerAuditDetails)
        assertFalse(adminBlocked.canSeeBalance)

        val auditableCard = ownerCard.copy(
            privacy = MemberPrivacySettings(
                showAvailableBalance = false,
                allowAdminDetailedAudit = true
            )
        )
        val adminAllowed = MemberAccountEngine.buildMemberProfileView(
            card = auditableCard,
            viewerUid = "uid_admin",
            viewerIsAdmin = true,
            allLedgerEntries = emptyList()
        )
        assertTrue(adminAllowed.canViewerAuditDetails)
        assertTrue(adminAllowed.canSeeBalance)
    }

    @Test
    fun roomMemberCardStorage_enforcesCrossFamilyIsolationAndDirectorySorting() = runTest {
        val famA = LocalFamilyEntity(
            familyId = "FAM_ALPHA_A",
            name = "Alpha Family",
            ownerId = "uid_admin_a",
            currencyCode = "BDT",
            memberUidsCsv = "uid_admin_a,uid_zara",
            adminUidsCsv = "uid_admin_a"
        )
        val cardA = LocalMemberCardEntity(
            familyId = famA.familyId,
            userId = "uid_admin_a",
            cardMemberId = "AAAA1111BBBB2222",
            familyName = famA.name,
            displayName = "Zahin Admin",
            role = FamilyRole.ADMIN.name,
            memberStatus = "ACTIVE",
            joinedAtMillis = 1000L
        )
        val cardA2 = LocalMemberCardEntity(
            familyId = famA.familyId,
            userId = "uid_zara",
            cardMemberId = "CCCC3333DDDD4444",
            familyName = famA.name,
            displayName = "Amina Member",
            role = FamilyRole.MEMBER.name,
            memberStatus = "ACTIVE",
            joinedAtMillis = 2000L
        )
        val cardB = LocalMemberCardEntity(
            familyId = "FAM_BETA_B",
            userId = "uid_other",
            cardMemberId = "EEEE5555FFFF6666",
            familyName = "Beta Family",
            displayName = "Other Family User",
            role = FamilyRole.ADMIN.name,
            memberStatus = "ACTIVE"
        )

        store.saveMemberCard(cardA)
        store.saveMemberCard(cardA2)
        store.saveMemberCard(cardB)

        val famACards = store.observeMemberCards(famA.familyId).first()
        val famBCards = store.observeMemberCards("FAM_BETA_B").first()
        assertEquals(2, famACards.size)
        assertEquals(1, famBCards.size)
        assertNotNull(db.memberCardDao().findByCardMemberId(famA.familyId, "AAAA1111BBBB2222"))
        assertNull(db.memberCardDao().findByCardMemberId("FAM_BETA_B", "AAAA1111BBBB2222"))

        // Verify directory sorting (ADMIN_FIRST vs NAME_ASC)
        val membersA = listOf(
            LocalFamilyMemberEntity(
                familyId = famA.familyId,
                userId = "uid_admin_a",
                displayName = "Zahin Admin",
                role = FamilyRole.ADMIN.name,
                cardMemberId = "AAAA1111BBBB2222",
                joinedAtMillis = 1000L
            ),
            LocalFamilyMemberEntity(
                familyId = famA.familyId,
                userId = "uid_zara",
                displayName = "Amina Member",
                role = FamilyRole.MEMBER.name,
                cardMemberId = "CCCC3333DDDD4444",
                joinedAtMillis = 2000L
            )
        )
        val sortedByRole = MemberAccountEngine.buildDirectoryCards(
            family = famA,
            members = membersA,
            memberCards = famACards,
            transactions = emptyList(),
            loans = emptyList(),
            savings = emptyList(),
            investments = emptyList(),
            viewerUid = "uid_admin_a",
            viewerIsAdmin = true,
            sortOption = MemberSortOption.ROLE_ADMIN_FIRST
        )
        assertEquals("uid_admin_a", sortedByRole.first().userId)

        val sortedByName = MemberAccountEngine.buildDirectoryCards(
            family = famA,
            members = membersA,
            memberCards = famACards,
            transactions = emptyList(),
            loans = emptyList(),
            savings = emptyList(),
            investments = emptyList(),
            viewerUid = "uid_admin_a",
            viewerIsAdmin = true,
            sortOption = MemberSortOption.NAME_ASC
        )
        assertEquals("uid_zara", sortedByName.first().userId)

        // Purging Family A does not remove Family B
        store.purgeFamilyCache(famA.familyId)
        assertEquals(0, store.observeMemberCards(famA.familyId).first().size)
        assertEquals(1, store.observeMemberCards("FAM_BETA_B").first().size)
    }
}
