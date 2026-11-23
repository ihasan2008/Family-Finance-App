package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.core.firebase.AppCheckStatus
import com.example.core.firebase.AuthService
import com.example.core.firebase.FirebaseConfigSnapshot
import com.example.core.firebase.SyncConnectionState
import com.example.core.local.LocalAuditLogEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.model.ThemeMode
import com.example.core.sync.ConflictResolutionPolicy
import com.example.ui.screens.modules.OfflineSyncCenterCard
import com.example.ui.theme.MoneySmallStyle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    currentUserId: String,
    userDisplayName: String,
    userEmail: String,
    activeFamily: LocalFamilyEntity?,
    themeMode: ThemeMode,
    connectionState: SyncConnectionState,
    configSnapshot: FirebaseConfigSnapshot,
    appCheckStatus: AppCheckStatus,
    databaseHealth: DatabaseHealthSnapshot = DatabaseHealthSnapshot(),
    recentAuditLogs: List<LocalAuditLogEntity> = emptyList(),
    conflictPolicy: ConflictResolutionPolicy = ConflictResolutionPolicy.MANUAL_REVIEW,
    outboxItems: List<LocalSyncOutboxEntity> = emptyList(),
    syncConflicts: List<LocalSyncConflictEntity> = emptyList(),
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectConflictPolicy: (ConflictResolutionPolicy) -> Unit = {},
    onResolveConflict: (String, ConflictResolutionPolicy) -> Unit = { _, _ -> },
    onSyncNow: () -> Unit,
    onBeforeSignOutPurge: suspend () -> Unit = {},
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val authService = remember { AuthService() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appearance / Theme Switcher
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Appearance & Theme",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = "Switch between Light, Dark, and System themes. Preference persists locally via DataStore and syncs to your profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onSelectTheme(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Firebase Environment, Connection & Database Status
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Firebase & Local Persistence",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = configSnapshot.environment.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (connectionState == SyncConnectionState.OFFLINE_QUEUED) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (connectionState == SyncConnectionState.OFFLINE_QUEUED) {
                                    Icons.Outlined.CloudOff
                                } else {
                                    Icons.Outlined.CloudDone
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = connectionState.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = connectionState.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    DetailLine("Firebase Project", configSnapshot.projectId)
                    DetailLine("Firestore DB ID", configSnapshot.firestoreDatabaseId)
                    DetailLine("App Check", appCheckStatus.label)
                    DetailLine("Local Engine", databaseHealth.localEngineName)
                    DetailLine("Local Encryption", databaseHealth.encryptionEngineName)
                    DetailLine("Active Family ID", activeFamily?.familyId ?: "None selected")
                    DetailLine("User UID", currentUserId)
                    DetailLine(
                        "Scoped Records",
                        "${databaseHealth.totalFinancialRecords} financial • ${databaseHealth.auditLogsCount} audit"
                    )
                    DetailLine(
                        "Offline Sync Queue",
                        "${databaseHealth.pendingSyncCount} pending • ${databaseHealth.failedSyncCount} retry"
                    )

                    if (recentAuditLogs.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "Recent Family Audit Trail",
                            style = MaterialTheme.typography.labelLarge
                        )
                        recentAuditLogs.take(3).forEach { log ->
                            Text(
                                text = "• [${log.entityCollection}] ${log.action}: ${log.summary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onSyncNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("settings_sync_button")
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Cloud Sync Check")
                    }
                }
            }

            // Offline-First Sync & Conflict Resolution Center
            OfflineSyncCenterCard(
                databaseHealth = databaseHealth,
                conflictPolicy = conflictPolicy,
                outboxItems = outboxItems,
                syncConflicts = syncConflicts,
                onSelectConflictPolicy = onSelectConflictPolicy,
                onResolveConflict = onResolveConflict,
                onSyncNow = onSyncNow
            )

            // Account & Session
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Account Session",
                        style = MaterialTheme.typography.titleMedium
                    )
                    DetailLine("Name", userDisplayName)
                    DetailLine("Email", userEmail)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Button(
                        onClick = {
                            scope.launch {
                                onBeforeSignOutPurge()
                                authService.signOutSession(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onSignOutComplete = onSignedOut
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MoneySmallStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
