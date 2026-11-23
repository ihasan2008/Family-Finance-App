package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalSyncConflictEntity
import com.example.core.local.LocalSyncOutboxEntity
import com.example.core.model.DatabaseHealthSnapshot
import com.example.core.sync.ConflictResolutionPolicy
import com.example.core.sync.ConflictStatus
import com.example.core.sync.ConflictType
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.WarningAmber

@Composable
fun OfflineSyncCenterCard(
    databaseHealth: DatabaseHealthSnapshot,
    conflictPolicy: ConflictResolutionPolicy,
    outboxItems: List<LocalSyncOutboxEntity>,
    syncConflicts: List<LocalSyncConflictEntity>,
    onSelectConflictPolicy: (ConflictResolutionPolicy) -> Unit,
    onResolveConflict: (String, ConflictResolutionPolicy) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unresolved = syncConflicts.filter { it.status == ConflictStatus.UNRESOLVED.name }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Offline Sync & Conflict Resolution",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (unresolved.isNotEmpty()) {
                        ExpenseCoral.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = if (unresolved.isNotEmpty()) "${unresolved.size} Conflict(s)" else "Healthy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unresolved.isNotEmpty()) ExpenseCoral else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${databaseHealth.encryptionEngineName} • ${databaseHealth.cachedReportsCount} cached report(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Conflict Resolution Policy",
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConflictResolutionPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = conflictPolicy == policy,
                        onClick = { onSelectConflictPolicy(policy) },
                        label = {
                            Text(
                                when (policy) {
                                    ConflictResolutionPolicy.MANUAL_REVIEW -> "Manual Review"
                                    ConflictResolutionPolicy.SERVER_WINS -> "Cloud Wins"
                                    ConflictResolutionPolicy.CLIENT_WINS -> "Device Wins"
                                }
                            )
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("conflict_policy_${policy.name.lowercase()}")
                    )
                }
            }
            Text(
                text = conflictPolicy.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (outboxItems.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Pending Outbox Operations (${outboxItems.size})",
                    style = MaterialTheme.typography.labelLarge
                )
                outboxItems.take(5).forEach { op ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${op.operation} • ${op.collectionName}/${op.documentId.take(12)} (v${op.syncVersion}, base v${op.baseSyncVersion})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (op.lastError != null) {
                                    "Retry #${op.retryCount}: ${op.lastError}"
                                } else {
                                    "Queued opId ${op.operationId.take(14)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (op.lastError != null) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (unresolved.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.SyncProblem, contentDescription = null, tint = ExpenseCoral, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Conflicts Needing Review (${unresolved.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = ExpenseCoral
                    )
                }
                unresolved.take(5).forEach { conflict ->
                    val typeLabel = ConflictType.fromRaw(conflict.conflictType).label
                    val isBlocked = conflict.conflictType == ConflictType.CROSS_FAMILY_VIOLATION.name ||
                        conflict.conflictType == ConflictType.UNAUTHORIZED_ACTOR.name
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, ExpenseCoral.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$typeLabel • ${conflict.collectionName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ExpenseCoral
                            )
                            Text(
                                text = "This device (v${conflict.localVersion}): ${conflict.localSummary}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Cloud (v${conflict.remoteVersion}): ${conflict.remoteSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onResolveConflict(conflict.conflictId, ConflictResolutionPolicy.SERVER_WINS) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("resolve_server_${conflict.conflictId}")
                                ) {
                                    Text("Accept Cloud")
                                }
                                if (!isBlocked) {
                                    Button(
                                        onClick = { onResolveConflict(conflict.conflictId, ConflictResolutionPolicy.CLIENT_WINS) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                            .testTag("resolve_client_${conflict.conflictId}")
                                    ) {
                                        Text("Keep Mine")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onSyncNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("sync_center_run_button")
            ) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Synchronize Pending Operations")
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "Offline Security Scope: Room records, cached reports, and chat history are encrypted via AndroidKeyStore AES-256-GCM and wiped on sign-out or session revocation. Firestore SDK's internal offline cache remains stored in private Android app storage, and SMS OTP / Passkey challenges require live connectivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
