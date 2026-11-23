package com.example.ui.screens.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalMyJoinRequestEntity
import com.example.core.model.FamilyInvitationDoc
import com.example.core.model.FamilyJoinRequestDoc
import com.example.core.model.InvitationStatus
import com.example.core.model.JoinRequestStatus
import com.example.ui.components.SectionHeader
import com.example.ui.theme.MoneySmallStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FamilyInvitationsAndApprovalsSection(
    isAdmin: Boolean,
    invitations: List<FamilyInvitationDoc>,
    joinRequests: List<FamilyJoinRequestDoc>,
    myJoinRequests: List<LocalMyJoinRequestEntity>,
    isBusy: Boolean,
    onApproveRequest: (requesterUid: String) -> Unit,
    onRejectRequest: (requesterUid: String) -> Unit,
    onCopyInvitationToken: (String) -> Unit,
    onRevokeInvitation: (invitationId: String, tokenHash: String) -> Unit,
    onRefreshMyJoinRequests: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (isAdmin) {
            val pending = joinRequests.filter { it.status == JoinRequestStatus.PENDING_APPROVAL.name }
            SectionHeader(
                title = "Member Approvals (${pending.size})",
                subtitle = "Verified invitation requests waiting for your Admin decision"
            )
            if (pending.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.HowToReg,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "No pending join approvals right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                pending.forEach { request ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = request.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${request.relationLabel ?: "Member"} • ${request.contactSummary ?: request.userId.take(10)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "Role: ${request.requestedRole}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onApproveRequest(request.userId) },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("approve_request_${request.userId}")
                                ) {
                                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Approve")
                                }
                                OutlinedButton(
                                    onClick = { onRejectRequest(request.userId) },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("reject_request_${request.userId}")
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }
            }

            SectionHeader(
                title = "Family Invitations (${invitations.size})",
                subtitle = "Expiring tokens visible only to Family Admins"
            )
            if (invitations.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "No invitation tokens generated yet. Tap Invite Member above to issue an expiring token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                invitations.forEach { inv ->
                    val expMillis = inv.expiresAt?.toDate()?.time ?: 0L
                    val isExpired = expMillis in 1..System.currentTimeMillis()
                    val statusLabel = when {
                        inv.status == InvitationStatus.REVOKED.name -> "REVOKED"
                        inv.status == InvitationStatus.USED.name -> "USED"
                        isExpired -> "EXPIRED"
                        else -> "ACTIVE"
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = inv.tokenPreview,
                                    style = MoneySmallStyle,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$statusLabel • Role: ${inv.targetRole} • Uses: ${inv.usedCount}/${inv.maxUses}" +
                                        if (expMillis > 0L) " • Exp: ${dateFmt.format(Date(expMillis))}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!inv.inviteeHint.isNullOrBlank()) {
                                    Text(
                                        text = "Note: ${inv.inviteeHint}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (statusLabel == "ACTIVE" && !inv.token.isNullOrBlank()) {
                                    IconButton(
                                        onClick = { onCopyInvitationToken(inv.token) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy invitation token",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    TextButton(
                                        onClick = { onRevokeInvitation(inv.invitationId, inv.tokenHash) },
                                        enabled = !isBusy
                                    ) {
                                        Text("Revoke", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (myJoinRequests.isNotEmpty()) {
            SectionHeader(
                title = "Your Submitted Join Requests",
                subtitle = "Families appear in your workspace as soon as Admin approves",
                actionLabel = "Refresh",
                onActionClick = onRefreshMyJoinRequests
            )
            myJoinRequests.forEach { req ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = req.familyName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Token ${req.tokenPreview} • ${req.relationLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = JoinRequestStatus.fromRaw(req.status).label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            IconButton(onClick = onRefreshMyJoinRequests) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh status", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
