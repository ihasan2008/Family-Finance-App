package com.example.ui.screens.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.model.AuthValidation
import com.example.core.model.FamilyRole
import com.example.ui.components.SectionHeader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilyMembersSection(
    family: LocalFamilyEntity,
    members: List<LocalFamilyMemberEntity>,
    currentUserId: String,
    isAdmin: Boolean,
    onManageMember: (LocalFamilyMemberEntity) -> Unit,
    onOpenMemberCards: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "Member Directory (${members.size})",
            subtitle = "Scoped strictly to Family ID ${family.familyId}",
            actionLabel = if (onOpenMemberCards != null) "Member Cards" else null,
            onActionClick = onOpenMemberCards
        )
        if (onOpenMemberCards != null) {
            OutlinedButton(
                onClick = onOpenMemberCards,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("open_member_cards_from_family_button")
            ) {
                Icon(Icons.Outlined.Badge, contentDescription = null)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Open Digital Member Cards & Individual Accounts")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Primary Admin: Single Primary Admin controls approvals, member suspension, settings & Family Password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Member: Verified mobile number serves as masked Member ID. Can record shared finance & join chat per permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (members.isEmpty()) {
            Text(
                text = "Syncing member directory...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            members.forEach { member ->
                val isPrimaryOwner = member.userId == family.ownerId
                val memberRole = FamilyRole.fromRaw(member.role)
                val isSuspended = member.memberStatus.equals("SUSPENDED", ignoreCase = true) ||
                    family.isSuspended(member.userId)
                val maskedMemberId = member.maskedPhone?.takeIf { it.isNotBlank() }
                    ?: AuthValidation.maskPhoneNumber(member.memberId).takeIf { member.memberId.isNotBlank() }
                    ?: member.userId.take(10)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("member_card_${member.userId}"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = if (isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = member.displayName + if (member.userId == currentUserId) " (You)" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${member.relationLabel ?: memberRole.label} • Member ID: $maskedMemberId",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isSuspended) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    }
                                ) {
                                    Text(
                                        text = if (isSuspended) "SUSPENDED" else "ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSuspended) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (memberRole == FamilyRole.ADMIN) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    }
                                ) {
                                    Text(
                                        text = if (isPrimaryOwner) "Primary Admin" else "Member",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (memberRole == FamilyRole.ADMIN) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                if (isAdmin) {
                                    IconButton(
                                        onClick = { onManageMember(member) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("manage_member_${member.userId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ManageAccounts,
                                            contentDescription = "Manage ${member.displayName}",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PermissionBadge("Create Records", (memberRole == FamilyRole.ADMIN || member.canCreateRecords) && !isSuspended)
                            PermissionBadge("Shared Finances", (memberRole == FamilyRole.ADMIN || member.canViewSharedFinances) && !isSuspended)
                            PermissionBadge("Family Chat", (memberRole == FamilyRole.ADMIN || member.canParticipateInChat) && !isSuspended)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(label: String, enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        }
    ) {
        Text(
            text = if (enabled) "$label: Allowed" else "$label: Off",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
