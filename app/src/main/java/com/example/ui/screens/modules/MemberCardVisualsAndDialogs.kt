package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.model.DigitalMemberCardData
import com.example.core.model.FamilyRole
import com.example.core.model.MemberLedgerApprovalStatus
import com.example.core.model.MemberLedgerEntry
import com.example.core.model.MemberPrivacySettings
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.MoneyMediumStyle
import com.example.ui.theme.MoneySmallStyle
import com.example.ui.theme.WarningAmber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy 'at' hh:mm a", Locale.US)

fun formatMemberLedgerDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(fullDateTimeFormatter)

@Composable
fun MemberAvatar(
    fullName: String,
    photoUrl: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val initials = remember(fullName) {
        val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "FM"
        }
    }
    var imageFailed by remember(photoUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank() && !imageFailed) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "$fullName profile photo",
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun DigitalMemberCardVisual(
    card: DigitalMemberCardData,
    isCurrentUser: Boolean,
    onClick: () -> Unit,
    onCopyMemberId: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSuspended = card.membershipStatus.equals("SUSPENDED", ignoreCase = true)
    val isAdmin = card.role == FamilyRole.ADMIN

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("digital_member_card_${card.userId}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isCurrentUser) 1.5.dp else 1.dp,
            color = when {
                isSuspended -> MaterialTheme.colorScheme.error
                isCurrentUser -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // Premium Family Finance card branding band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = if (isSuspended) {
                                listOf(
                                    MaterialTheme.colorScheme.errorContainer,
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else {
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            }
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_family_finance_logo),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuspended) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = card.membershipStatus,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSuspended) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MemberAvatar(
                        fullName = card.fullName,
                        photoUrl = card.photoUrl,
                        size = 52.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = card.fullName + if (isCurrentUser) " (You)" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = card.familyName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isAdmin) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isAdmin) Icons.Outlined.AdminPanelSettings else Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = if (isAdmin) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isAdmin) "Admin" else "Member",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAdmin) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }

                // 16-char Alphanumeric Member ID Strip
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MEMBER ID (16-CHAR)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = card.cardMemberId.chunked(4).joinToString(" "),
                                style = MoneySmallStyle.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onCopyMemberId(card.cardMemberId) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("copy_card_id_${card.userId}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_member_card_id),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Available Balance Row (Privacy-respecting)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Available Balance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (card.canViewerSeeAvailableBalance) {
                            val balance = card.rawSummary.availableBalanceMinor
                            Text(
                                text = MoneyCalculator.formatMinorUnits(balance, card.rawSummary.currencyCode),
                                style = MoneyMediumStyle.copy(fontWeight = FontWeight.Bold),
                                color = if (balance >= 0L) IncomeGreen else ExpenseCoral
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Hidden by Privacy",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Badge,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Open Profile",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemberCardPrivacySettingsDialog(
    initialSettings: MemberPrivacySettings,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemberPrivacySettings) -> Unit
) {
    var settings by remember(initialSettings) { mutableStateOf(initialSettings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Member Card Privacy") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Control what other members can view on your Digital Member Card. Private mobile numbers, passwords, and date of birth are never shared here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrivacyToggleRow(
                    title = "Show Available Balance",
                    subtitle = "Display net available balance on your directory card and profile",
                    checked = settings.showAvailableBalance,
                    testTag = "privacy_toggle_balance",
                    onCheckedChange = { settings = settings.copy(showAvailableBalance = it) }
                )
                PrivacyToggleRow(
                    title = "Show Income & Expense Totals",
                    subtitle = "Share ordinary income and expense totals",
                    checked = settings.showIncomeAndExpense,
                    testTag = "privacy_toggle_income_expense",
                    onCheckedChange = { settings = settings.copy(showIncomeAndExpense = it) }
                )
                PrivacyToggleRow(
                    title = "Show Family Contributions",
                    subtitle = "Allow members to view your personal-to-family contributions",
                    checked = settings.showFamilyContributions,
                    testTag = "privacy_toggle_contributions",
                    onCheckedChange = { settings = settings.copy(showFamilyContributions = it) }
                )
                PrivacyToggleRow(
                    title = "Show Loans",
                    subtitle = "Share outstanding loans given and received",
                    checked = settings.showLoans,
                    testTag = "privacy_toggle_loans",
                    onCheckedChange = { settings = settings.copy(showLoans = it) }
                )
                PrivacyToggleRow(
                    title = "Show Savings",
                    subtitle = "Share savings reserve balance",
                    checked = settings.showSavings,
                    testTag = "privacy_toggle_savings",
                    onCheckedChange = { settings = settings.copy(showSavings = it) }
                )
                PrivacyToggleRow(
                    title = "Show Investments",
                    subtitle = "Share investment principal and separate profit/loss",
                    checked = settings.showInvestments,
                    testTag = "privacy_toggle_investments",
                    onCheckedChange = { settings = settings.copy(showInvestments = it) }
                )
                PrivacyToggleRow(
                    title = "Show Individual Ledger History",
                    subtitle = "Share itemized transaction history entries",
                    checked = settings.showTransactionHistory,
                    testTag = "privacy_toggle_history",
                    onCheckedChange = { settings = settings.copy(showTransactionHistory = it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyToggleRow(
                    title = "Allow Family Admin Audit",
                    subtitle = "Allow Family Admin to inspect hidden categories for family governance (all views logged)",
                    checked = settings.allowAdminDetailedAudit,
                    testTag = "privacy_toggle_admin_audit",
                    onCheckedChange = { settings = settings.copy(allowAdminDetailedAudit = it) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(settings) },
                enabled = !isBusy,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("save_member_privacy_button")
            ) {
                Text("Save Privacy")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PrivacyToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
fun MemberLedgerEntryDetailsDialog(
    entry: MemberLedgerEntry,
    onCopyId: (label: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    val approvalColor = when (entry.approvalStatus) {
        MemberLedgerApprovalStatus.APPROVED -> IncomeGreen
        MemberLedgerApprovalStatus.PENDING_REVIEW -> WarningAmber
        MemberLedgerApprovalStatus.VOIDED -> ExpenseCoral
        MemberLedgerApprovalStatus.SETTLED -> MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "${entry.type.label} • ${MoneyCalculator.formatMinorUnits(entry.amountMinor, entry.currencyCode)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DetailFieldRow(
                    label = "Transaction ID",
                    value = entry.transactionId,
                    onCopy = { onCopyId("Transaction ID", entry.transactionId) }
                )
                DetailFieldRow(label = "Transaction Title", value = entry.title)
                DetailFieldRow(label = "Transaction Type", value = entry.type.label)
                DetailFieldRow(
                    label = "Amount",
                    value = MoneyCalculator.formatMinorUnits(entry.amountMinor, entry.currencyCode)
                )
                DetailFieldRow(
                    label = "Date and Time",
                    value = formatMemberLedgerDateTime(entry.occurredAtMillis)
                )
                DetailFieldRow(label = "Related Member", value = entry.relatedMemberName)
                DetailFieldRow(
                    label = "Approval Status",
                    value = entry.approvalStatus.label,
                    highlightColor = approvalColor
                )
                DetailFieldRow(
                    label = "Reference ID",
                    value = entry.referenceId,
                    onCopy = { onCopyId("Reference ID", entry.referenceId) }
                )
                DetailFieldRow(label = "Description", value = entry.description)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sync Status",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModuleSyncStatusChip(syncStatus = entry.syncStatus)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("close_ledger_details_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailFieldRow(
    label: String,
    value: String,
    highlightColor: androidx.compose.ui.graphics.Color? = null,
    onCopy: (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = highlightColor ?: MaterialTheme.colorScheme.onSurface
                )
            }
            if (onCopy != null) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MemberLedgerDateRangeDialog(
    initialStartMillis: Long?,
    initialEndMillis: Long?,
    onDismiss: () -> Unit,
    onApply: (startMillis: Long?, endMillis: Long?) -> Unit
) {
    var startMillis by remember(initialStartMillis) { mutableStateOf(initialStartMillis) }
    var endMillis by remember(initialEndMillis) { mutableStateOf(initialEndMillis) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Ledger Date Range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleDatePickerRow(
                    label = "Start Date",
                    selectedMillis = startMillis,
                    allowClear = true,
                    testTag = "member_ledger_custom_start_picker",
                    onDateSelected = { startMillis = it }
                )
                ModuleDatePickerRow(
                    label = "End Date",
                    selectedMillis = endMillis,
                    allowClear = true,
                    testTag = "member_ledger_custom_end_picker",
                    onDateSelected = { endMillis = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(startMillis, endMillis) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("apply_member_ledger_date_range")
            ) {
                Text("Apply Range")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Cancel")
            }
        }
    )
}
