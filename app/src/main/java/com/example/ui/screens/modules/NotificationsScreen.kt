package com.example.ui.screens.modules

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Unpublished
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalNotificationEntity
import com.example.core.model.AppModule
import com.example.core.model.NotificationType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val notificationTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

private enum class NotificationFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    MESSAGES("Messages"),
    CALLS_MEET("Calls & Meet"),
    TRANSACTIONS("Transactions"),
    DECISIONS("Approvals"),
    INVITES("Invites"),
    LOANS("Loan Due"),
    BUDGETS("Budgets"),
    SAVINGS("Savings")
}

@Composable
fun NotificationsScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    notifications: List<LocalNotificationEntity>,
    unreadCount: Int,
    notificationsEnabled: Boolean,
    onToggleNotificationsEnabled: (Boolean) -> Unit,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onArchive: (String) -> Unit,
    onOpenModule: (AppModule) -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Notifications",
            moduleSubtitle = "Create or join a family workspace to receive real-time alerts for chat messages, transactions, loans, budgets, and savings milestones.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(NotificationFilter.ALL) }
    var permissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            onToggleNotificationsEnabled(true)
        }
    }

    val filtered = remember(notifications, selectedFilter, currentUserId) {
        notifications.filter { item ->
            when (selectedFilter) {
                NotificationFilter.ALL -> true
                NotificationFilter.UNREAD -> item.isUnreadFor(currentUserId)
                NotificationFilter.MESSAGES -> item.notificationType == NotificationType.NEW_MESSAGE.name
                NotificationFilter.CALLS_MEET -> item.notificationType in setOf(
                    NotificationType.CALL_INCOMING.name,
                    NotificationType.CALL_MISSED.name,
                    NotificationType.MEETING_INVITE.name,
                    NotificationType.MEETING_STARTED.name
                )
                NotificationFilter.TRANSACTIONS -> item.notificationType == NotificationType.NEW_TRANSACTION.name
                NotificationFilter.DECISIONS -> item.notificationType in setOf(
                    NotificationType.PROPOSAL_SUBMITTED.name,
                    NotificationType.PROPOSAL_VOTE_CAST.name,
                    NotificationType.PROPOSAL_APPROVED.name,
                    NotificationType.PROPOSAL_REJECTED.name,
                    NotificationType.PROPOSAL_NEAR_DEADLINE.name,
                    NotificationType.PROPOSAL_EXPIRED.name,
                    NotificationType.PROPOSAL_EXECUTED.name
                )
                NotificationFilter.INVITES -> item.notificationType == NotificationType.FAMILY_INVITATION.name ||
                    item.notificationType == NotificationType.INVITATION_APPROVAL.name
                NotificationFilter.LOANS -> item.notificationType == NotificationType.LOAN_DUE_DATE.name
                NotificationFilter.BUDGETS -> item.notificationType == NotificationType.BUDGET_ALERT.name
                NotificationFilter.SAVINGS -> item.notificationType == NotificationType.SAVINGS_MILESTONE.name
            }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${activeFamily.name} Alerts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (unreadCount > 0) {
                                    "$unreadCount unread • ${notifications.size} total"
                                } else {
                                    "All caught up • ${notifications.size} total"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onMarkAllRead,
                            enabled = unreadCount > 0,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mark_all_notifications_read")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DoneAll,
                                contentDescription = stringResource(R.string.cd_mark_all_read),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Mark all read")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (notificationsEnabled) {
                                    Icons.Outlined.NotificationsActive
                                } else {
                                    Icons.Outlined.NotificationsOff
                                },
                                contentDescription = null,
                                tint = if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text(
                                    text = "Device & FCM Push Alerts",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Messages, transactions, invites, due dates, budgets & milestones",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = onToggleNotificationsEnabled,
                            modifier = Modifier.testTag("notifications_enabled_switch")
                        )
                    }

                    if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Allow Android notifications to receive push alerts when the app is in background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("grant_notification_permission_button")
                                ) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotificationFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("notif_filter_${filter.name.lowercase()}")
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ModuleEmptyRecordsCard(
                        icon = Icons.Outlined.NotificationsActive,
                        title = "No Notifications Here",
                        subtitle = "Family chat alerts, new transactions, loan due dates, budget warnings, and savings milestones appear here."
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("notifications_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.notificationId }) { item ->
                        NotificationItemCard(
                            item = item,
                            isUnread = item.isUnreadFor(currentUserId),
                            onClick = {
                                if (item.isUnreadFor(currentUserId)) {
                                    onMarkRead(item.notificationId)
                                }
                                resolveTargetModule(item)?.let(onOpenModule)
                            },
                            onMarkRead = { onMarkRead(item.notificationId) },
                            onArchive = { onArchive(item.notificationId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItemCard(
    item: LocalNotificationEntity,
    isUnread: Boolean,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onArchive: () -> Unit
) {
    val type = remember(item.notificationType) { NotificationType.fromRaw(item.notificationType) }
    val timeLabel = remember(item.createdAtMillis) {
        Instant.ofEpochMilli(item.createdAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(notificationTimeFormatter)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("notification_card_${item.notificationId}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isUnread) 1.5.dp else 1.dp,
            color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (isUnread) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconForNotificationType(type),
                        contentDescription = null,
                        tint = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isUnread) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {}
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${type.label} • $timeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isUnread) {
                            TextButton(
                                onClick = onMarkRead,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("mark_read_${item.notificationId}")
                            ) {
                                Text("Read")
                            }
                        }
                        IconButton(
                            onClick = onArchive,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("archive_notif_${item.notificationId}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Archive,
                                contentDescription = stringResource(R.string.cd_archive_notification),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun iconForNotificationType(type: NotificationType): ImageVector = when (type) {
    NotificationType.NEW_MESSAGE -> Icons.AutoMirrored.Outlined.Chat
    NotificationType.NEW_TRANSACTION -> Icons.Outlined.ReceiptLong
    NotificationType.FAMILY_INVITATION -> Icons.Outlined.Diversity3
    NotificationType.INVITATION_APPROVAL -> Icons.Outlined.CheckCircle
    NotificationType.LOAN_DUE_DATE -> Icons.Outlined.EventBusy
    NotificationType.BUDGET_ALERT -> Icons.Outlined.WarningAmber
    NotificationType.SAVINGS_MILESTONE -> Icons.Outlined.Savings
    NotificationType.CALL_INCOMING -> Icons.Outlined.Call
    NotificationType.CALL_MISSED -> Icons.Outlined.CallMissed
    NotificationType.MEETING_INVITE -> Icons.Outlined.Groups
    NotificationType.MEETING_STARTED -> Icons.Outlined.VideoCall
    NotificationType.PROPOSAL_SUBMITTED -> Icons.Outlined.HowToVote
    NotificationType.PROPOSAL_VOTE_CAST -> Icons.Outlined.HowToVote
    NotificationType.PROPOSAL_APPROVED -> Icons.Outlined.TaskAlt
    NotificationType.PROPOSAL_REJECTED -> Icons.Outlined.Unpublished
    NotificationType.PROPOSAL_NEAR_DEADLINE -> Icons.Outlined.HourglassBottom
    NotificationType.PROPOSAL_EXPIRED -> Icons.Outlined.EventBusy
    NotificationType.PROPOSAL_EXECUTED -> Icons.Outlined.CheckCircle
    NotificationType.GENERAL -> Icons.Outlined.NotificationsActive
}

private fun resolveTargetModule(item: LocalNotificationEntity): AppModule? {
    val raw = item.deepLinkModule?.takeIf { it.isNotBlank() }
        ?: NotificationType.fromRaw(item.notificationType).defaultModuleName
    return AppModule.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
