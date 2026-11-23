package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalChatMessageEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.model.ChatRetentionOption
import com.example.core.model.RecordStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chatTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilyChatScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    activeMembers: List<LocalFamilyMemberEntity>,
    messages: List<LocalChatMessageEntity>,
    syncStatusLabel: String,
    onSendMessage: (String) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    isLoadingOlder: Boolean = false,
    hasMoreOlder: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    onEditMessage: (messageId: String, newText: String) -> Unit = { _, _ -> },
    onDeleteMessage: (messageId: String) -> Unit = {},
    onMarkMessagesRead: (messageIds: List<String>) -> Unit = {},
    onUpdateRetentionDays: (Int) -> Unit = {},
    onPurgeExpiredChats: () -> Unit = {},
    onOpenMeet: () -> Unit = {}
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Family Chat",
            moduleSubtitle = "Create or join a family workspace to exchange offline-cached encrypted messages with family members.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val currentMember = activeMembers.firstOrNull { it.userId == currentUserId }
    val memberRoleLookup = remember(activeMembers) {
        activeMembers.associate {
            it.userId to (it.relationLabel?.takeIf { rel -> rel.isNotBlank() } ?: it.role)
        }
    }
    val isAdmin = activeFamily.isAdmin(currentUserId)
    val canSend = activeFamily.memberChatEnabled && (isAdmin || (currentMember?.canParticipateInChat ?: true))
    var draftText by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<LocalChatMessageEntity?>(null) }
    var deletingMessage by remember { mutableStateOf<LocalChatMessageEntity?>(null) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var lastTailMessageId by remember(activeFamily.familyId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val filteredMessages = remember(messages, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) messages
        else messages.filter { msg ->
            msg.messageText.contains(q, ignoreCase = true) ||
                msg.senderDisplayName.contains(q, ignoreCase = true)
        }
    }

    LaunchedEffect(messages, currentUserId) {
        val unreadIds = messages
            .filter {
                it.recordStatus == RecordStatus.ACTIVE.name &&
                    it.senderUserId != currentUserId &&
                    currentUserId !in it.readByUids
            }
            .map { it.messageId }
        if (unreadIds.isNotEmpty()) {
            onMarkMessagesRead(unreadIds)
        }
    }

    val showLoadOlderHeader = searchQuery.isBlank() && (hasMoreOlder || messages.size >= 25)
    LaunchedEffect(filteredMessages.lastOrNull()?.messageId, searchQuery, showLoadOlderHeader) {
        val newTail = filteredMessages.lastOrNull()?.messageId
        if (searchQuery.isBlank() && newTail != null && newTail != lastTailMessageId) {
            lastTailMessageId = newTail
            val headerOffset = if (showLoadOlderHeader) 1 else 0
            listState.animateScrollToItem(headerOffset + filteredMessages.lastIndex)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${activeFamily.name} Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (unreadCount > 0) {
                                    Badge {
                                        Text("$unreadCount")
                                    }
                                }
                            }
                            val retentionLabel = ChatRetentionOption.fromDays(activeFamily.chatRetentionDays).label
                            Text(
                                text = "${messages.size} cached • Retention: $retentionLabel • $syncStatusLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onOpenMeet,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_open_meet_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.VideoCall,
                                    contentDescription = stringResource(R.string.cd_open_meet)
                                )
                            }
                            IconButton(
                                onClick = {
                                    searchVisible = !searchVisible
                                    if (!searchVisible) searchQuery = ""
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_search_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.cd_search_chat)
                                )
                            }
                            IconButton(
                                onClick = { showRetentionDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_retention_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoDelete,
                                    contentDescription = stringResource(R.string.cd_chat_retention)
                                )
                            }
                            IconButton(
                                onClick = onSyncNow,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_sync_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = "Sync chat"
                                )
                            }
                        }
                    }

                    if (searchVisible) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search messages or sender name...") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Clear,
                                            contentDescription = stringResource(R.string.cd_clear_search)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_search_input")
                        )
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = "${filteredMessages.size} matching message(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (filteredMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ModuleEmptyRecordsCard(
                        icon = if (searchQuery.isNotBlank()) Icons.Outlined.Search else Icons.AutoMirrored.Outlined.Chat,
                        title = if (searchQuery.isNotBlank()) "No Matching Messages" else "No Family Messages Yet",
                        subtitle = if (searchQuery.isNotBlank()) {
                            "Try another keyword or clear the search filter."
                        } else {
                            "Discuss budgets, savings milestones, or planned purchases. Messages queue offline in encrypted SQLite and sync automatically."
                        }
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("chat_message_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showLoadOlderHeader) {
                        item(key = "load_older_header") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = onLoadOlderMessages,
                                    enabled = !isLoadingOlder,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("load_older_chats_button")
                                ) {
                                    if (isLoadingOlder) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.History,
                                            contentDescription = stringResource(R.string.cd_load_older_messages),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isLoadingOlder) "Loading history..." else "Load older messages")
                                }
                            }
                        }
                    }

                    items(filteredMessages, key = { it.messageId }) { msg ->
                        val isMine = msg.senderUserId == currentUserId
                        val resolvedRole = msg.senderRoleLabel.ifBlank {
                            memberRoleLookup[msg.senderUserId].orEmpty()
                        }
                        ChatMessageBubble(
                            message = msg,
                            roleLabel = resolvedRole,
                            isMine = isMine,
                            canEdit = isMine && !msg.isVoided && canSend,
                            canDelete = (isMine || isAdmin) && !msg.isVoided,
                            currentUserId = currentUserId,
                            onEditClick = { editingMessage = msg },
                            onDeleteClick = { deletingMessage = msg }
                        )
                    }
                }
            }

            if (!canSend) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (!activeFamily.memberChatEnabled) {
                                "Family chat is currently paused by Family Admin settings."
                            } else {
                                "Your account currently has read-only access to family chat."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { if (it.length <= 1000) draftText = it },
                        placeholder = { Text("Message ${activeFamily.name} (works offline)...") },
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )
                    FilledIconButton(
                        onClick = {
                            val clean = draftText.trim()
                            if (clean.isNotEmpty()) {
                                onSendMessage(clean)
                                draftText = ""
                            }
                        },
                        enabled = draftText.isNotBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("send_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.cd_send_chat_message)
                        )
                    }
                }
            }
        }
    }

    val editTarget = editingMessage
    if (editTarget != null) {
        var editedText by remember(editTarget.messageId) { mutableStateOf(editTarget.messageText) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { if (it.length <= 1000) editedText = it },
                    label = { Text("Message") },
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_message_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = editedText.trim()
                        if (clean.isNotEmpty()) {
                            onEditMessage(editTarget.messageId, clean)
                            editingMessage = null
                        }
                    },
                    enabled = editedText.isNotBlank() && editedText.trim() != editTarget.messageText,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_edit_message")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingMessage = null },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val deleteTarget = deletingMessage
    if (deleteTarget != null) {
        ModuleDeleteConfirmDialog(
            title = "Delete Family Message?",
            message = "This replaces the message text with a deleted placeholder and records an audit trail entry for your family.",
            isBusy = false,
            testTag = "confirm_delete_message",
            onDismiss = { deletingMessage = null },
            onConfirm = {
                onDeleteMessage(deleteTarget.messageId)
                deletingMessage = null
            }
        )
    }

    if (showRetentionDialog) {
        var selectedDays by remember(activeFamily.chatRetentionDays) {
            mutableIntStateOf(activeFamily.chatRetentionDays)
        }
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text("Chat Retention Policy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isAdmin) {
                            "Choose how long family chat messages are retained before automatic cleanup."
                        } else {
                            "Configured by your Family Admin: ${ChatRetentionOption.fromDays(activeFamily.chatRetentionDays).label}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isAdmin) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ChatRetentionOption.entries.forEach { option ->
                                FilterChip(
                                    selected = selectedDays == option.days,
                                    onClick = { selectedDays = option.days },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("chat_retention_option_${option.days}")
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onPurgeExpiredChats()
                            showRetentionDialog = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("purge_expired_chats_button")
                    ) {
                        Text("Purge Expired Messages Now")
                    }
                }
            },
            confirmButton = {
                if (isAdmin) {
                    Button(
                        onClick = {
                            onUpdateRetentionDays(selectedDays)
                            showRetentionDialog = false
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("save_chat_retention_button")
                    ) {
                        Text("Apply Policy")
                    }
                } else {
                    TextButton(onClick = { showRetentionDialog = false }) { Text("Close") }
                }
            },
            dismissButton = {
                if (isAdmin) {
                    TextButton(onClick = { showRetentionDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: LocalChatMessageEntity,
    roleLabel: String,
    isMine: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    currentUserId: String,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val containerColor = if (message.isVoided) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    } else if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val textColor = if (isMine && !message.isVoided) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val timeText = remember(message.createdAtMillis) {
        Instant.ofEpochMilli(message.createdAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(chatTimeFormatter)
    }
    val senderName = if (isMine) "You" else message.senderDisplayName.ifBlank { "Family Member" }
    val initials = remember(message.senderDisplayName) {
        message.senderDisplayName
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "FM" }
    }
    val otherReadersCount = remember(message.readByUids, message.senderUserId, currentUserId) {
        message.readByUids.count { it != message.senderUserId }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isMine) {
            Surface(
                modifier = Modifier
                    .padding(top = 4.dp, end = 8.dp)
                    .size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )
                    if (roleLabel.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = roleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    ModuleSyncStatusChip(syncStatus = message.syncStatus)
                }

                Text(
                    text = if (message.isVoided) "[Message deleted]" else message.messageText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (message.isVoided) FontStyle.Italic else FontStyle.Normal,
                    color = if (message.isVoided) MaterialTheme.colorScheme.onSurfaceVariant else textColor
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (message.isEdited) "$timeText • Edited" else timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isMine && !message.isVoided) {
                            Icon(
                                imageVector = if (otherReadersCount > 0) Icons.Outlined.DoneAll else Icons.Outlined.Done,
                                contentDescription = if (otherReadersCount > 0) "Read by $otherReadersCount" else "Sent",
                                tint = if (otherReadersCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            if (otherReadersCount > 0) {
                                Text(
                                    text = "Read by $otherReadersCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (canEdit || canDelete) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (canEdit) {
                                IconButton(
                                    onClick = onEditClick,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("edit_msg_${message.messageId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.cd_edit_message),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (canDelete) {
                                IconButton(
                                    onClick = onDeleteClick,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("delete_msg_${message.messageId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_delete_message),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
