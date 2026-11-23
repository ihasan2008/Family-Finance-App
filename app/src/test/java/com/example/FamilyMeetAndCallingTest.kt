package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.comms.ActiveCallMediaUiState
import com.example.core.comms.ActiveMeetingMediaUiState
import com.example.core.comms.LiveKitParticipantMedia
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.LocalCallRecordEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalMeetingEntity
import com.example.core.local.abstraction.SqliteRoomDatabaseStore
import com.example.core.model.AudioOutputRoute
import com.example.core.model.CallConnectionQuality
import com.example.core.model.CallMediaType
import com.example.core.model.CallSessionDoc
import com.example.core.model.CallStatus
import com.example.core.model.FamilyRole
import com.example.core.model.MeetInviteHelper
import com.example.core.model.MeetingParticipantDoc
import com.example.core.model.MeetingParticipantState
import com.example.core.model.MeetingStatus
import com.example.ui.screens.modules.FamilyMeetScreen
import com.example.ui.screens.modules.IncomingCallBannerOverlay
import com.example.ui.screens.modules.LiveMeetingRoomOverlay
import com.example.ui.screens.modules.OneToOneCallOverlay
import com.example.ui.theme.FamilyFinanceTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FamilyMeetAndCallingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: FamilyFinanceDatabase
    private lateinit var store: SqliteRoomDatabaseStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        db = Room.inMemoryDatabaseBuilder(context, FamilyFinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SqliteRoomDatabaseStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleFamily(
        familyId: String = "fam_meet_1",
        name: String = "Khan Household"
    ) = LocalFamilyEntity(
        familyId = familyId,
        name = name,
        ownerId = "uid_alice",
        createdByUserId = "uid_alice",
        currencyCode = "BDT",
        memberUidsCsv = "uid_alice,uid_bob",
        adminUidsCsv = "uid_alice"
    )

    private fun sampleMembers(familyId: String = "fam_meet_1"): List<LocalFamilyMemberEntity> = listOf(
        LocalFamilyMemberEntity(
            familyId = familyId,
            userId = "uid_alice",
            displayName = "Alice Khan",
            role = FamilyRole.ADMIN.name,
            relationLabel = "Parent"
        ),
        LocalFamilyMemberEntity(
            familyId = familyId,
            userId = "uid_bob",
            displayName = "Bob Khan",
            role = FamilyRole.MEMBER.name,
            relationLabel = "Co-Owner"
        )
    )

    @Test
    fun meetInviteHelper_parsesUrlMeetingIdAndInviteCode() {
        val code = MeetInviteHelper.generateInviteCode()
        assertTrue(code.matches(Regex("FF-[A-Z2-9]{4}-[A-Z2-9]{4}")))

        val roomName = MeetInviteHelper.buildRoomName("fam_alpha", "mtg_991")
        assertEquals("ff_fam_alpha_mtg_991", roomName)

        val link = MeetInviteHelper.buildInviteLink("fam_alpha", "mtg_991", code)
        val fromLink = MeetInviteHelper.parseInviteInput(link)
        assertEquals("fam_alpha", fromLink.familyId)
        assertEquals("mtg_991", fromLink.meetingId)
        assertEquals(code, fromLink.codeOrIdToken)

        val fromId = MeetInviteHelper.parseInviteInput("mtg_12345")
        assertNull(fromId.familyId)
        assertEquals("mtg_12345", fromId.meetingId)
        assertEquals("MTG_12345", fromId.codeOrIdToken)

        val fromCode = MeetInviteHelper.parseInviteInput("  ff-abcd-efgh ")
        assertNull(fromCode.meetingId)
        assertEquals("FF-ABCD-EFGH", fromCode.codeOrIdToken)
    }

    @Test
    fun roomEncryptedCommsStorage_isolatesAndPurgesByFamily() = runTest {
        val famA = sampleFamily("fam_A", "Alpha Family")
        val famB = sampleFamily("fam_B", "Beta Family")
        db.familyDao().upsertFamily(famA)
        db.familyDao().upsertFamily(famB)

        val callA = LocalCallRecordEntity(
            callId = "call_a1",
            familyId = famA.familyId,
            callerUid = "uid_alice",
            calleeUid = "uid_bob",
            callerName = "Alice Sensitive",
            calleeName = "Bob Sensitive",
            mediaType = CallMediaType.VIDEO.name,
            status = CallStatus.ENDED.name,
            networkQuality = CallConnectionQuality.EXCELLENT.name,
            durationSeconds = 95
        )
        val callB = LocalCallRecordEntity(
            callId = "call_b1",
            familyId = famB.familyId,
            callerUid = "uid_alice",
            calleeUid = "uid_bob",
            callerName = "Beta Caller",
            calleeName = "Beta Callee",
            mediaType = CallMediaType.AUDIO.name,
            status = CallStatus.MISSED.name
        )

        val meetingA = LocalMeetingEntity(
            meetingId = "mtg_a1",
            familyId = famA.familyId,
            roomName = "ff_fam_A_mtg_a1",
            title = "Confidential Budget Sync",
            hostUid = "uid_alice",
            hostName = "Alice Secret",
            inviteCode = "FF-TEST-1111",
            inviteLink = "https://gen-lang-client-0265273815.web.app/meet?familyId=fam_A&meetingId=mtg_a1&code=FF-TEST-1111",
            status = MeetingStatus.LIVE.name
        )
        val meetingB = LocalMeetingEntity(
            meetingId = "mtg_b1",
            familyId = famB.familyId,
            roomName = "ff_fam_B_mtg_b1",
            title = "Beta Standup",
            hostUid = "uid_alice",
            hostName = "Alice Beta",
            inviteCode = "FF-BETA-2222",
            inviteLink = "https://gen-lang-client-0265273815.web.app/meet?familyId=fam_B&meetingId=mtg_b1&code=FF-BETA-2222",
            status = MeetingStatus.SCHEDULED.name,
            isScheduled = true
        )

        store.saveCallRecord(callA)
        store.saveCallRecord(callB)
        store.saveMeeting(meetingA)
        store.saveMeeting(meetingB)

        // Verify raw SQLite columns store AES-256-GCM ciphertext, not plaintext
        val rawCallA = db.callHistoryDao().getById("call_a1")!!
        val rawMeetingA = db.meetingDao().getById("mtg_a1")!!
        assertNotEquals("Alice Sensitive", rawCallA.callerName)
        assertNotEquals("Confidential Budget Sync", rawMeetingA.title)

        // Verify store decrypts and strictly isolates by familyId
        val famACalls = store.observeCallHistory(famA.familyId, "uid_alice").first()
        val famBCalls = store.observeCallHistory(famB.familyId, "uid_alice").first()
        assertEquals(1, famACalls.size)
        assertEquals("Alice Sensitive", famACalls.first().callerName)
        assertEquals(1, famBCalls.size)
        assertEquals("Beta Caller", famBCalls.first().callerName)

        val foundByCode = store.findMeetingByInviteCode(famA.familyId, "FF-TEST-1111")
        assertEquals("Confidential Budget Sync", foundByCode?.title)
        assertNull(store.findMeetingByInviteCode(famB.familyId, "FF-TEST-1111"))

        // Purge Family A only -> Family B must survive intact
        store.purgeFamilyCache(famA.familyId)
        assertTrue(store.observeCallHistory(famA.familyId, "uid_alice").first().isEmpty())
        assertTrue(store.observeMeetings(famA.familyId).first().isEmpty())
        assertEquals(1, store.observeCallHistory(famB.familyId, "uid_alice").first().size)
        assertEquals(1, store.observeMeetings(famB.familyId).first().size)
    }

    @Test
    fun familyMeetScreen_createsMeetingWithInvitedMembersAndJoinsByCode() {
        val family = sampleFamily()
        val members = sampleMembers(family.familyId)
        var capturedTitle: String? = null
        var capturedInvited: List<String> = emptyList()
        var joinedCode: String? = null

        composeRule.setContent {
            FamilyFinanceTheme {
                FamilyMeetScreen(
                    currentUserId = "uid_alice",
                    activeFamily = family,
                    activeMembers = members,
                    meetings = emptyList(),
                    callHistory = emptyList(),
                    onStartCall = { _, _, _, _ -> },
                    onCreateMeeting = { title, _, _, invited, _, _, _, _ ->
                        capturedTitle = title
                        capturedInvited = invited
                    },
                    onJoinMeetingByInput = { input, _, _, _, _ ->
                        joinedCode = input
                    },
                    onJoinExistingMeeting = { _, _, _ -> },
                    onEndMeetingForEveryone = {},
                    onNotifyMessage = {},
                    onNavigateToFamilyManagement = {}
                )
            }
        }

        // Open 1-to-1 Calls tab and confirm Bob appears
        composeRule.onNodeWithTag("meet_tab_calls").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("call_member_card_uid_bob").assertIsDisplayed()

        // Return to Group Meet tab
        composeRule.onNodeWithTag("meet_tab_meetings").performClick()
        composeRule.waitForIdle()

        // Join by code quick card
        composeRule.onNodeWithTag("meet_join_code_input").performTextInput("FF-JOIN-7788")
        composeRule.onNodeWithTag("join_meet_button").performClick()
        composeRule.waitForIdle()
        assertEquals("FF-JOIN-7788", joinedCode)

        // Open Create Meeting dialog & invite Bob
        composeRule.onNodeWithTag("create_meeting_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_meeting_title_input").performTextReplacement("Monthly Budget Review")
        composeRule.onNodeWithTag("invite_member_uid_bob").performClick()
        composeRule.onNodeWithTag("confirm_create_meeting_button").performClick()
        composeRule.waitForIdle()

        assertEquals("Monthly Budget Review", capturedTitle)
        assertEquals(listOf("uid_bob"), capturedInvited)
    }

    @Test
    fun incomingCallBanner_displaysCallerAndRejects() {
        val session = CallSessionDoc(
            callId = "call_ui_1",
            familyId = "fam_meet_1",
            callerUid = "uid_bob",
            calleeUid = "uid_alice",
            callerName = "Bob Khan",
            calleeName = "Alice Khan",
            mediaType = CallMediaType.AUDIO.name,
            status = CallStatus.RINGING.name
        )
        var rejected = false

        composeRule.setContent {
            FamilyFinanceTheme {
                IncomingCallBannerOverlay(
                    incomingCall = session,
                    onAccept = { _, _, _ -> },
                    onReject = { rejected = true }
                )
            }
        }

        composeRule.onNodeWithTag("incoming_call_card").assertIsDisplayed()
        composeRule.onNodeWithText("Bob Khan").assertIsDisplayed()
        composeRule.onNodeWithTag("reject_call_button").performClick()
        composeRule.waitForIdle()
        assertTrue(rejected)
    }

    @Test
    fun oneToOneCallOverlay_rendersTimerQualityAndControls() {
        val session = CallSessionDoc(
            callId = "call_ui_1",
            familyId = "fam_meet_1",
            callerUid = "uid_bob",
            calleeUid = "uid_alice",
            callerName = "Bob Khan",
            calleeName = "Alice Khan",
            mediaType = CallMediaType.AUDIO.name,
            status = CallStatus.ACTIVE.name
        )
        var micToggled = false
        var ended = false

        composeRule.setContent {
            FamilyFinanceTheme {
                OneToOneCallOverlay(
                    currentUserId = "uid_alice",
                    callSession = session,
                    mediaState = ActiveCallMediaUiState(
                        activeCallId = "call_ui_1",
                        micMuted = false,
                        cameraEnabled = false,
                        audioRoute = AudioOutputRoute.EARPIECE,
                        connectionQuality = CallConnectionQuality.GOOD,
                        isPeerConnected = true
                    ),
                    durationSeconds = 75,
                    isInPipMode = false,
                    onToggleMic = { micToggled = true },
                    onToggleCamera = {},
                    onSwitchCamera = {},
                    onCycleAudioRoute = {},
                    onTriggerIceRestart = {},
                    onEnterPipMode = {},
                    onEndCall = { ended = true }
                )
            }
        }

        composeRule.onNodeWithTag("active_call_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("01:15").assertIsDisplayed()
        composeRule.onNodeWithText(CallConnectionQuality.GOOD.label).assertIsDisplayed()

        composeRule.onNodeWithTag("call_toggle_mic_button").performClick()
        composeRule.onNodeWithTag("end_call_button").performClick()
        composeRule.waitForIdle()
        assertTrue(micToggled)
        assertTrue(ended)
    }

    @Test
    fun liveMeetingRoomOverlay_rendersParticipantsActiveSpeakerAndHostControls() {
        val family = sampleFamily()
        val meeting = LocalMeetingEntity(
            meetingId = "mtg_live_1",
            familyId = family.familyId,
            roomName = "ff_fam_meet_1_mtg_live_1",
            title = "Q2 Savings Huddle",
            hostUid = "uid_alice",
            hostName = "Alice Khan",
            inviteCode = "FF-SYNC-2026",
            inviteLink = "https://gen-lang-client-0265273815.web.app/meet?familyId=fam_meet_1&meetingId=mtg_live_1&code=FF-SYNC-2026",
            status = MeetingStatus.LIVE.name
        )
        val participantsMedia = ActiveMeetingMediaUiState(
            activeMeetingId = "mtg_live_1",
            roomName = meeting.roomName,
            isConnected = true,
            localMicEnabled = true,
            localCameraEnabled = true,
            participants = listOf(
                LiveKitParticipantMedia(
                    identity = "uid_alice",
                    displayName = "Alice Khan",
                    isLocal = true,
                    isSpeaking = false,
                    micEnabled = true,
                    cameraEnabled = false,
                    screenSharing = false,
                    audioTrackSid = "TR_ALICE"
                ),
                LiveKitParticipantMedia(
                    identity = "uid_bob",
                    displayName = "Bob Khan",
                    isLocal = false,
                    isSpeaking = true,
                    micEnabled = true,
                    cameraEnabled = false,
                    screenSharing = false,
                    audioTrackSid = "TR_BOB"
                )
            ),
            activeSpeakerIdentities = setOf("uid_bob")
        )
        val participantsDocs = listOf(
            MeetingParticipantDoc(
                id = "uid_alice",
                participantUid = "uid_alice",
                meetingId = meeting.meetingId,
                familyId = family.familyId,
                displayName = "Alice Khan",
                roleLabel = "Parent",
                isHost = true,
                state = MeetingParticipantState.JOINED.name
            ),
            MeetingParticipantDoc(
                id = "uid_bob",
                participantUid = "uid_bob",
                meetingId = meeting.meetingId,
                familyId = family.familyId,
                displayName = "Bob Khan",
                roleLabel = "Co-Owner",
                isHost = false,
                state = MeetingParticipantState.JOINED.name
            )
        )

        var mutedUid: String? = null
        var mutedFlag: Boolean? = null
        var removedUid: String? = null
        var endedForAll = false

        composeRule.setContent {
            FamilyFinanceTheme {
                LiveMeetingRoomOverlay(
                    currentUserId = "uid_alice",
                    activeFamily = family,
                    meeting = meeting,
                    mediaState = participantsMedia,
                    participantsMetadata = participantsDocs,
                    durationSeconds = 125,
                    onToggleMic = {},
                    onToggleCamera = {},
                    onSwitchCamera = {},
                    onCycleAudioRoute = {},
                    onToggleScreenShare = { _, _ -> },
                    onMuteParticipant = { uid, muted, _ ->
                        mutedUid = uid
                        mutedFlag = muted
                    },
                    onRemoveParticipant = { uid ->
                        removedUid = uid
                    },
                    onLeaveMeeting = {},
                    onEndMeetingForEveryone = { endedForAll = true },
                    onNotifyMessage = {}
                )
            }
        }

        composeRule.onNodeWithTag("live_meeting_room_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("meeting_duration_text").assertTextContains("02:05")
        composeRule.onNodeWithTag("meeting_participant_count").assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("participant_tile_uid_alice").assertIsDisplayed()
        composeRule.onNodeWithTag("participant_tile_uid_bob").assertIsDisplayed()

        // Open Participants & Host Controls sheet
        composeRule.onNodeWithTag("meeting_participants_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("host_mute_uid_bob").performClick()
        composeRule.waitForIdle()
        assertEquals("uid_bob", mutedUid)
        assertEquals(true, mutedFlag)

        composeRule.onNodeWithTag("host_remove_uid_bob").performClick()
        composeRule.waitForIdle()
        assertEquals("uid_bob", removedUid)

        composeRule.onNodeWithTag("end_meeting_for_all_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_end_meeting_for_all").performClick()
        composeRule.waitForIdle()
        assertTrue(endedForAll)
        assertFalse(mutedUid == null)
    }
}
