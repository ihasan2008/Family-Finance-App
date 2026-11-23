package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.local.abstraction.LocalEntityCipherMapper
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AuditAction
import com.example.core.model.FamilyRole
import com.example.core.model.NotificationType
import com.example.core.model.RecordStatus
import com.example.core.model.SyncState
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.OfflineSyncEngine
import com.example.core.sync.RemoteSyncGateway
import com.example.core.sync.RemoteWriteOutcome
import com.example.ui.screens.modules.FamilyChatScreen
import com.example.ui.screens.modules.NotificationsScreen
import com.example.ui.theme.FamilyFinanceTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunicationAndNotificationsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore
    private val cipher = LocalEntityCipherMapper()

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

    private suspend fun seedFamilyAndMember(
        familyId: String = "fam_chat_1",
        uid: String = "uid_alice",
        chatEnabled: Boolean = true,
        canChat: Boolean = true
    ): LocalFamilyEntity {
        val fam = LocalFamilyEntity(
            familyId = familyId,
            name = "Khan Family",
            ownerId = "uid_alice",
            createdByUserId = "uid_alice",
            currencyCode = "USD",
            memberUidsCsv = "uid_alice,uid_bob",
            adminUidsCsv = "uid_alice",
            memberChatEnabled = chatEnabled,
            chatRetentionDays = 30
        )
        db.familyDao().upsertFamily(fam)
        db.familyMemberDao().upsertMember(
            LocalFamilyMemberEntity(
                familyId = familyId,
                userId = uid,
                displayName = "Alice",
                role = FamilyRole.MEMBER.name,
                relationLabel = "Parent",
                canCreateRecords = true,
                canViewSharedFinances = true,
                canParticipateInChat = canChat
            )
        )
        return fam
    }

    @Test
    fun chatEditSoftDeleteAndReadReceipts_updateStateAndBumpVersion() = runTest {
        val fam = seedFamilyAndMember()
        val msg = LocalChatMessageEntity(
            messageId = "msg_101",
            familyId = fam.familyId,
            senderUserId = "uid_alice",
            senderDisplayName = "Alice",
            senderRoleLabel = "Parent",
            messageText = "Groceries budget check",
            readByUidsCsv = "uid_alice",
            createdAtMillis = 1_000_000L
        )
        val createAudit = LocalAuditLogEntity(
            auditId = "aud_chat_1",
            familyId = fam.familyId,
            actorUserId = "uid_alice",
            entityCollection = "chats",
            entityId = msg.messageId,
            action = AuditAction.CREATE.name,
            summary = "Sent message"
        )
        store.saveChatMessage(msg, createAudit)

        // Edit message
        val editAudit = createAudit.copy(auditId = "aud_chat_2", action = AuditAction.UPDATE.name)
        val edited = store.editChatMessageLocal(
            messageId = msg.messageId,
            familyId = fam.familyId,
            actorUserId = "uid_alice",
            newText = "Groceries budget updated to $450",
            auditEntry = editAudit
        )
        assertNotNull(edited)
        assertTrue(edited!!.isEdited)
        assertEquals(2, edited.syncVersion)
        assertEquals("Groceries budget updated to $450", edited.messageText)

        // Read receipt by uid_bob
        val markedCount = store.markChatMessagesReadLocal(fam.familyId, "uid_bob", listOf(msg.messageId))
        assertEquals(1, markedCount)
        val afterRead = store.getChatMessageById(msg.messageId)!!
        assertTrue(afterRead.isReadBy("uid_bob"))
        assertEquals(listOf("uid_alice", "uid_bob"), afterRead.readByUids)

        // Soft delete -> VOIDED
        val deleteAudit = createAudit.copy(auditId = "aud_chat_3", action = AuditAction.DELETE.name)
        val voided = store.softDeleteChatMessageLocal(msg.messageId, fam.familyId, "uid_alice", deleteAudit)
        assertNotNull(voided)
        assertTrue(voided!!.isVoided)
        assertEquals("[Message deleted]", voided.messageText)
        assertEquals("uid_alice", voided.deletedByUserId)
    }

    @Test
    fun chatPaginationAndRetentionPurge_respectFamilyBoundaryAndExpiry() = runTest {
        seedFamilyAndMember("fam_a", "uid_alice")
        seedFamilyAndMember("fam_b", "uid_carol")

        // Insert 5 messages in fam_a, 2 expired, 3 active, plus 1 in fam_b
        val now = 2_000_000_000L
        listOf(100L to (now - 10_000L), 200L to (now - 5_000L), 300L to null, 400L to (now + 100_000L), 500L to null)
            .forEachIndexed { idx, (ts, exp) ->
                store.saveChatMessage(
                    LocalChatMessageEntity(
                        messageId = "msg_a_$idx",
                        familyId = "fam_a",
                        senderUserId = "uid_alice",
                        senderDisplayName = "Alice",
                        messageText = "Message $idx",
                        expiresAtMillis = exp,
                        createdAtMillis = ts
                    ),
                    LocalAuditLogEntity(
                        auditId = "aud_p_$idx",
                        familyId = "fam_a",
                        actorUserId = "uid_alice",
                        entityCollection = "chats",
                        entityId = "msg_a_$idx",
                        action = AuditAction.CREATE.name,
                        summary = "Chat"
                    )
                )
            }
        store.saveChatMessage(
            LocalChatMessageEntity(
                messageId = "msg_b_1",
                familyId = "fam_b",
                senderUserId = "uid_carol",
                senderDisplayName = "Carol",
                messageText = "Private other family",
                createdAtMillis = 450L
            ),
            LocalAuditLogEntity(
                auditId = "aud_b_1",
                familyId = "fam_b",
                actorUserId = "uid_carol",
                entityCollection = "chats",
                entityId = "msg_b_1",
                action = AuditAction.CREATE.name,
                summary = "Chat"
            )
        )

        // Pagination returns chronological order for chat timeline UI: newest 2, then older 2 before 400L
        val page1 = store.getCachedChatsPage("fam_a", beforeMillis = null, limit = 2)
        assertEquals(listOf("msg_a_3", "msg_a_4"), page1.map { it.messageId })
        val page2 = store.getCachedChatsPage("fam_a", beforeMillis = 400L, limit = 2)
        assertEquals(listOf("msg_a_1", "msg_a_2"), page2.map { it.messageId })

        // Purge expired
        val purged = store.purgeExpiredChatsLocal(
            familyId = "fam_a",
            nowMillis = now,
            auditEntry = LocalAuditLogEntity(
                auditId = "aud_purge",
                familyId = "fam_a",
                actorUserId = "uid_alice",
                entityCollection = "chats",
                entityId = "fam_a",
                action = AuditAction.DELETE.name,
                summary = "Purge expired"
            )
        )
        assertEquals(2, purged.size)
        val remainingA = store.observeChats("fam_a").first()
        assertEquals(listOf("msg_a_2", "msg_a_3", "msg_a_4"), remainingA.map { it.messageId })
        // fam_b remains isolated and untouched
        assertEquals(1, store.observeChats("fam_b").first().size)
    }

    @Test
    fun notificationsEncryptionReadArchiveAndSyncEngineFieldUpdate() = runTest {
        val fam = seedFamilyAndMember("fam_ntf", "uid_alice")
        val rawNotif = LocalNotificationEntity(
            notificationId = "ntf_1",
            familyId = fam.familyId,
            recipientUserId = "ALL_MEMBERS",
            createdByUserId = "uid_bob",
            title = "Budget Alert: Food",
            body = "Food category exceeded $500 limit",
            notificationType = NotificationType.BUDGET_ALERT.name,
            relatedRecordId = "bud_1",
            deepLinkModule = "BUDGETS",
            readByUidsCsv = "uid_bob",
            createdAtMillis = 5_000L
        )
        val encrypted = cipher.encrypt(rawNotif)
        assertNotEquals(rawNotif.title, encrypted.title)
        assertNotEquals(rawNotif.body, encrypted.body)

        store.saveNotificationWithOutbox(rawNotif)
        val observed = store.observeNotifications(fam.familyId, "uid_alice").first()
        assertEquals(1, observed.size)
        assertTrue(observed.first().isUnreadFor("uid_alice"))
        assertFalse(observed.first().isUnreadFor("uid_bob"))

        var createdRemote = false
        var executedFieldUpdate = false
        val fakeGateway = object : RemoteSyncGateway {
            override suspend fun executeCreate(
                familyId: String,
                collection: String,
                docId: String,
                idempotencyKey: String,
                payload: Map<String, Any>,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome {
                if (collection == "notifications" && docId == "ntf_1") {
                    createdRemote = true
                }
                return RemoteWriteOutcome.Success(1)
            }

            override suspend fun executeUpdateVersionChecked(
                familyId: String,
                collection: String,
                docId: String,
                expectedBaseVersion: Int,
                nextVersion: Int,
                payload: Map<String, Any>,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome = RemoteWriteOutcome.Success(nextVersion)

            override suspend fun executeFieldUpdate(
                familyId: String,
                collection: String,
                docId: String,
                fields: Map<String, Any>,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome {
                if (collection == "notifications" && docId == "ntf_1") {
                    executedFieldUpdate = true
                }
                return RemoteWriteOutcome.Success(1)
            }

            override suspend fun executeDelete(
                familyId: String,
                collection: String,
                docId: String,
                expectedBaseVersion: Int,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome = RemoteWriteOutcome.Success(expectedBaseVersion)
        }
        val engine = OfflineSyncEngine(store, fakeGateway)
        val createSummary = engine.synchronize("uid_alice", fam, ConflictResolutionPolicy.CLIENT_WINS, isConnectivityOnline = true)
        assertEquals(1, createSummary.syncedCount)
        assertTrue(createdRemote)

        // Now mark synced notification read and verify field update routes cleanly via OfflineSyncEngine
        store.markNotificationReadLocal("ntf_1", fam.familyId, "uid_alice")
        val afterRead = store.observeNotifications(fam.familyId, "uid_alice").first().first()
        assertFalse(afterRead.isUnreadFor("uid_alice"))

        val updateSummary = engine.synchronize("uid_alice", fam, ConflictResolutionPolicy.CLIENT_WINS, isConnectivityOnline = true)
        assertEquals(1, updateSummary.syncedCount)
        assertTrue(executedFieldUpdate)

        // Archive notification hides it from active query
        store.archiveNotificationLocal("ntf_1", fam.familyId, "uid_alice")
        assertTrue(store.observeNotifications(fam.familyId, "uid_alice").first().isEmpty())
    }

    @Test
    fun offlineSyncEngine_blocksUnauthorizedChatWhenDisabled() = runTest {
        val fam = seedFamilyAndMember("fam_disabled", "uid_bob", chatEnabled = false, canChat = false)
        val msg = LocalChatMessageEntity(
            messageId = "msg_blocked",
            familyId = fam.familyId,
            senderUserId = "uid_bob",
            senderDisplayName = "Bob",
            messageText = "Trying while paused"
        )
        db.chatDao().upsert(cipher.encrypt(msg))
        db.syncOutboxDao().upsert(
            LocalSyncOutboxEntity(
                outboxId = "obx_chat_block",
                familyId = fam.familyId,
                actorUserId = "uid_bob",
                collectionName = "chats",
                documentId = "msg_blocked",
                operation = "CREATE",
                status = "PENDING"
            )
        )
        val gateway = object : RemoteSyncGateway {
            override suspend fun executeCreate(
                familyId: String,
                collection: String,
                docId: String,
                idempotencyKey: String,
                payload: Map<String, Any>,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome = RemoteWriteOutcome.Success(1)

            override suspend fun executeUpdateVersionChecked(
                familyId: String,
                collection: String,
                docId: String,
                expectedBaseVersion: Int,
                nextVersion: Int,
                payload: Map<String, Any>,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome = RemoteWriteOutcome.Success(nextVersion)

            override suspend fun executeDelete(
                familyId: String,
                collection: String,
                docId: String,
                expectedBaseVersion: Int,
                auditId: String?,
                auditPayload: Map<String, Any>?
            ): RemoteWriteOutcome = RemoteWriteOutcome.Success(expectedBaseVersion)
        }
        val engine = OfflineSyncEngine(store, gateway)
        val result = engine.synchronize("uid_bob", fam, ConflictResolutionPolicy.MANUAL_REVIEW, isConnectivityOnline = true)
        assertEquals(1, result.rejectedCount)
        assertEquals(0, result.syncedCount)
    }

    @Test
    fun familyChatScreen_supportsSearchEditDeleteAndPagination() {
        var sentMessage = ""
        var editedPair: Pair<String, String>? = null
        var deletedId = ""
        var loadedOlder = false

        val family = LocalFamilyEntity(
            familyId = "fam_ui",
            name = "Rahman Family",
            ownerId = "uid_alice",
            createdByUserId = "uid_alice",
            currencyCode = "USD",
            memberUidsCsv = "uid_alice,uid_bob",
            adminUidsCsv = "uid_alice",
            chatRetentionDays = 90
        )
        val msgs = listOf(
            LocalChatMessageEntity(
                messageId = "m1",
                familyId = "fam_ui",
                senderUserId = "uid_alice",
                senderDisplayName = "Alice",
                senderRoleLabel = "Admin",
                messageText = "Did we pay the electricity bill?",
                readByUidsCsv = "uid_alice,uid_bob",
                syncStatus = SyncState.SYNCED.name,
                createdAtMillis = 10_000L
            ),
            LocalChatMessageEntity(
                messageId = "m2",
                familyId = "fam_ui",
                senderUserId = "uid_bob",
                senderDisplayName = "Bob",
                senderRoleLabel = "Member",
                messageText = "Yes, settled this morning!",
                readByUidsCsv = "uid_bob",
                syncStatus = SyncState.SYNCED.name,
                createdAtMillis = 20_000L
            )
        )

        composeRule.setContent {
            FamilyFinanceTheme {
                FamilyChatScreen(
                    currentUserId = "uid_alice",
                    activeFamily = family,
                    activeMembers = emptyList(),
                    messages = msgs,
                    syncStatusLabel = "Synced",
                    onSendMessage = { sentMessage = it },
                    onSyncNow = {},
                    onNavigateToFamilyManagement = {},
                    unreadCount = 1,
                    hasMoreOlder = true,
                    onLoadOlderMessages = { loadedOlder = true },
                    onEditMessage = { id, text -> editedPair = id to text },
                    onDeleteMessage = { deletedId = it }
                )
            }
        }

        // Scroll to top header and click "Load older messages"
        composeRule.onNodeWithTag("chat_message_list").performScrollToNode(hasTestTag("load_older_chats_button"))
        composeRule.onNodeWithTag("load_older_chats_button").performClick()
        assertTrue(loadedOlder)

        // Read status rendered on own message
        composeRule.onNodeWithText("Read by 1").assertIsDisplayed()

        // Search toggles and filters list
        composeRule.onNodeWithTag("chat_search_toggle").performClick()
        composeRule.onNodeWithTag("chat_search_input").performTextInput("electricity")
        composeRule.onNodeWithText("1 matching message(s)").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_search_toggle").performClick()

        // Edit dialog flow
        composeRule.onNodeWithTag("chat_message_list").performScrollToNode(hasTestTag("edit_msg_m1"))
        composeRule.onNodeWithTag("edit_msg_m1").performScrollTo().performClick()
        composeRule.onNodeWithTag("edit_message_input").performTextReplacement("Electricity bill paid?")
        composeRule.onNodeWithTag("confirm_edit_message").performClick()
        assertEquals("m1" to "Electricity bill paid?", editedPair)

        // Delete dialog flow
        composeRule.onNodeWithTag("chat_message_list").performScrollToNode(hasTestTag("delete_msg_m2"))
        composeRule.onNodeWithTag("delete_msg_m2").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_delete_message").performClick()
        assertEquals("m2", deletedId)

        // Send new message
        composeRule.onNodeWithTag("chat_message_input").performTextInput("Thanks Bob!")
        composeRule.onNodeWithTag("send_chat_button").performClick()
        assertEquals("Thanks Bob!", sentMessage)
    }

    @Test
    fun notificationsScreen_filtersAndMarksRead() {
        var markAllCalled = false
        var markedSingleId = ""
        var archivedId = ""

        val family = LocalFamilyEntity(
            familyId = "fam_ui",
            name = "Rahman Family",
            ownerId = "uid_alice",
            createdByUserId = "uid_alice",
            currencyCode = "USD",
            memberUidsCsv = "uid_alice",
            adminUidsCsv = "uid_alice"
        )
        val notifications = listOf(
            LocalNotificationEntity(
                notificationId = "n_loan",
                familyId = "fam_ui",
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = "uid_bob",
                title = "Loan Due Soon",
                body = "Auto loan due tomorrow",
                notificationType = NotificationType.LOAN_DUE_DATE.name,
                readByUidsCsv = ""
            ),
            LocalNotificationEntity(
                notificationId = "n_sav",
                familyId = "fam_ui",
                recipientUserId = "ALL_MEMBERS",
                createdByUserId = "uid_alice",
                title = "Savings Milestone: 50%",
                body = "Emergency Fund reached 50%",
                notificationType = NotificationType.SAVINGS_MILESTONE.name,
                readByUidsCsv = "uid_alice"
            )
        )

        composeRule.setContent {
            FamilyFinanceTheme {
                NotificationsScreen(
                    currentUserId = "uid_alice",
                    activeFamily = family,
                    notifications = notifications,
                    unreadCount = 1,
                    notificationsEnabled = true,
                    onToggleNotificationsEnabled = {},
                    onMarkRead = { markedSingleId = it },
                    onMarkAllRead = { markAllCalled = true },
                    onArchive = { archivedId = it },
                    onOpenModule = {},
                    onNavigateToFamilyManagement = {}
                )
            }
        }

        composeRule.onNodeWithText("Loan Due Soon").assertIsDisplayed()
        composeRule.onNodeWithTag("mark_read_n_loan").performScrollTo().performClick()
        assertEquals("n_loan", markedSingleId)

        composeRule.onNodeWithTag("mark_all_notifications_read").performClick()
        assertTrue(markAllCalled)

        composeRule.onNodeWithTag("notif_filter_loans").performScrollTo().performClick()
        composeRule.onNodeWithText("Loan Due Soon").assertIsDisplayed()

        composeRule.onNodeWithTag("archive_notif_n_loan").performScrollTo().performClick()
        assertEquals("n_loan", archivedId)
    }
}
