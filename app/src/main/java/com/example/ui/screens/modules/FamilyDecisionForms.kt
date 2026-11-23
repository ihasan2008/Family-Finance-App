package com.example.ui.screens.modules

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalApprovalSettingsEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalProposalEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.model.ApprovalRuleMode
import com.example.core.model.DecisionCategory
import com.example.core.model.EligibleVoterRule
import com.example.core.model.MoneyCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val formDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

@Composable
fun ProposalFormDialog(
    activeFamily: LocalFamilyEntity,
    settings: LocalApprovalSettingsEntity,
    savingsGoals: List<LocalSavingsEntity>,
    existingDraft: LocalProposalEntity?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSaveOrSubmit: (
        title: String,
        category: DecisionCategory,
        description: String,
        reason: String,
        amountMinor: Long,
        currency: String,
        relatedParty: String,
        supportingDocName: String?,
        supportingDocUri: String?,
        targetSavingsId: String?,
        proposedDateMillis: Long,
        votingDeadlineMillis: Long,
        submitNow: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    val defaultCurrency = existingDraft?.currency?.ifBlank { activeFamily.currencyCode }
        ?: activeFamily.currencyCode.ifBlank { "BDT" }
    val requiredCats = remember(settings) { settings.requiredCategorySet() }

    var title by remember { mutableStateOf(existingDraft?.title ?: "") }
    var category by remember {
        mutableStateOf(
            existingDraft?.let { DecisionCategory.fromRaw(it.category) } ?: DecisionCategory.LARGE_EXPENSE
        )
    }
    var amountText by remember {
        mutableStateOf(
            existingDraft?.let {
                MoneyCalculator.minorUnitsToInputString(it.amountMinor, it.currency)
            } ?: ""
        )
    }
    var currency by remember { mutableStateOf(defaultCurrency) }
    var relatedParty by remember { mutableStateOf(existingDraft?.relatedParty ?: "") }
    var reason by remember { mutableStateOf(existingDraft?.reason ?: "") }
    var description by remember { mutableStateOf(existingDraft?.description ?: "") }
    var supportingDocName by remember { mutableStateOf(existingDraft?.supportingDocName) }
    var supportingDocUri by remember { mutableStateOf(existingDraft?.supportingDocUri) }
    var targetSavingsId by remember {
        mutableStateOf(
            existingDraft?.targetSavingsId ?: savingsGoals.firstOrNull()?.recordId
        )
    }
    val proposedDateMillis = remember {
        existingDraft?.proposedDateMillis ?: System.currentTimeMillis()
    }
    var deadlineHours by remember {
        mutableIntStateOf(
            existingDraft?.let {
                (((it.votingDeadlineMillis - proposedDateMillis) / 3_600_000L).toInt()).coerceIn(1, 720)
            } ?: settings.defaultDeadlineHours.coerceIn(1, 720)
        )
    }
    var localError by remember { mutableStateOf<String?>(null) }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val resolvedName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "supporting_document"
            supportingDocName = resolvedName.take(160)
            supportingDocUri = uri.toString().take(300)
        }
    }

    val votingDeadlineMillis = proposedDateMillis + deadlineHours * 3_600_000L

    fun submitForm(submitNow: Boolean) {
        if (title.trim().length < 2) {
            localError = "Enter a proposal title (at least 2 characters)."
            return
        }
        val minorResult = MoneyCalculator.parseDecimalToMinorUnits(amountText, currency)
        val minor = minorResult.getOrElse { err ->
            localError = err.message ?: "Enter a valid positive amount."
            return
        }
        if (category == DecisionCategory.WITHDRAW_SAVINGS && targetSavingsId.isNullOrBlank()) {
            localError = "Select a family savings goal for this withdrawal proposal."
            return
        }
        localError = null
        onSaveOrSubmit(
            title,
            category,
            description,
            reason,
            minor,
            currency,
            relatedParty,
            supportingDocName,
            supportingDocUri,
            targetSavingsId,
            proposedDateMillis,
            votingDeadlineMillis,
            submitNow
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existingDraft != null) "Edit Draft Proposal" else "Create Financial Proposal",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Proposal Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proposal_title_input")
                )

                Text(
                    text = "Decision Category",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DecisionCategory.entries.forEach { cat ->
                        val isReq = cat in requiredCats
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = {
                                Text(if (isReq) cat.shortLabel else "${cat.shortLabel} (Opt)")
                            }
                        )
                    }
                }

                if (category == DecisionCategory.WITHDRAW_SAVINGS) {
                    Text(
                        text = "Target Savings Goal",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (savingsGoals.isEmpty()) {
                        Text(
                            text = "No active savings goals found in this family workspace.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        savingsGoals.forEach { goal ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (targetSavingsId == goal.recordId) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { targetSavingsId = goal.recordId }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = targetSavingsId == goal.recordId,
                                            onClick = { targetSavingsId = goal.recordId }
                                        )
                                        Text(goal.goalName, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(
                                        text = MoneyCalculator.formatMinorUnits(goal.effectiveCurrentMinor(), goal.currency),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(2f)
                            .testTag("proposal_amount_input")
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(5) },
                        label = { Text("Currency") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = relatedParty,
                    onValueChange = { relatedParty = it },
                    label = { Text("Related Member or Organization") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proposal_party_input")
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason / Purpose") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proposal_reason_input")
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description & Terms (optional)") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proposal_description_input")
                )

                // Zero-permission document attachment via SAF OpenDocument
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { docPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = stringResource(R.string.cd_attach_supporting_doc),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (supportingDocName != null) "Change Doc" else "Supporting Doc")
                    }
                    if (!supportingDocName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = supportingDocName.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = {
                                    supportingDocName = null
                                    supportingDocUri = null
                                }
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove document", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Text(
                    text = "Voting Deadline Window",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(24 to "24h", 48 to "48h", 72 to "72h", 168 to "7 Days", 336 to "14 Days").forEach { (hrs, lbl) ->
                        FilterChip(
                            selected = deadlineHours == hrs,
                            onClick = { deadlineHours = hrs },
                            label = { Text(lbl) }
                        )
                    }
                }
                Text(
                    text = "Proposed: ${formDateFmt.format(Instant.ofEpochMilli(proposedDateMillis).atZone(ZoneId.systemDefault()))}\n" +
                        "Deadline: ${formDateFmt.format(Instant.ofEpochMilli(votingDeadlineMillis).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submitForm(submitNow = false) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("proposal_save_draft_button")
                ) {
                    Text("Save Draft")
                }
                Button(
                    onClick = { submitForm(submitNow = true) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("proposal_submit_button")
                ) {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ApprovalRulesDialog(
    activeFamily: LocalFamilyEntity,
    activeMembers: List<LocalFamilyMemberEntity>,
    currentSettings: LocalApprovalSettingsEntity,
    isAdmin: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSaveRules: (
        ruleMode: ApprovalRuleMode,
        minApprovalPercent: Int,
        minQuorumCount: Int,
        defaultDeadlineHours: Int,
        allowCreatorVote: Boolean,
        allowVoteChangeBeforeDeadline: Boolean,
        eligibleVoterRule: EligibleVoterRule,
        specificEligibleUids: List<String>,
        requiredCategories: Set<DecisionCategory>,
        largeExpenseThresholdMinor: Long
    ) -> Unit
) {
    var ruleMode by remember { mutableStateOf(ApprovalRuleMode.fromRaw(currentSettings.ruleMode)) }
    var minPctFloat by remember { mutableFloatStateOf(currentSettings.minApprovalPercent.coerceIn(1, 100).toFloat()) }
    var minQuorum by remember { mutableIntStateOf(currentSettings.minQuorumCount.coerceIn(1, 20)) }
    var deadlineHours by remember { mutableIntStateOf(currentSettings.defaultDeadlineHours.coerceIn(1, 720)) }
    var allowCreatorVote by remember { mutableStateOf(currentSettings.allowCreatorVote) }
    var allowVoteChange by remember { mutableStateOf(currentSettings.allowVoteChangeBeforeDeadline) }
    var eligibleRule by remember { mutableStateOf(EligibleVoterRule.fromRaw(currentSettings.eligibleVoterRule)) }
    var specificUids by remember { mutableStateOf(currentSettings.specificEligibleUidList().toSet()) }
    var requiredCategories by remember { mutableStateOf(currentSettings.requiredCategorySet()) }
    var thresholdText by remember {
        mutableStateOf(
            MoneyCalculator.minorUnitsToInputString(
                currentSettings.largeExpenseThresholdMinor,
                activeFamily.currencyCode
            )
        )
    }

    val maxMembers = activeMembers.size.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isAdmin) "Configure Family Approval Rules" else "Family Approval Rules",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = if (isAdmin) {
                            "Admins configure governance rules transparently. Votes are cryptographically locked per member and cannot be secretly overridden."
                        } else {
                            "Read-only view: Only a Family Admin can modify household approval thresholds."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text("Approval Rule Mode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ApprovalRuleMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isAdmin) { ruleMode = mode },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = ruleMode == mode,
                            onClick = { if (isAdmin) ruleMode = mode },
                            enabled = isAdmin
                        )
                        Column {
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(mode.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (ruleMode == ApprovalRuleMode.MIN_PERCENTAGE) {
                    Text(
                        text = "Minimum Approval Percentage: ${minPctFloat.roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = minPctFloat,
                        onValueChange = { if (isAdmin) minPctFloat = it },
                        valueRange = 50f..100f,
                        enabled = isAdmin
                    )
                }

                // Minimum Voting Quorum stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Minimum Voting Quorum", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Required cast votes (max $maxMembers)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (isAdmin && minQuorum > 1) minQuorum -= 1 },
                            enabled = isAdmin && minQuorum > 1
                        ) { Text("-") }
                        Text("$minQuorum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { if (isAdmin && minQuorum < maxMembers) minQuorum += 1 },
                            enabled = isAdmin && minQuorum < maxMembers
                        ) { Text("+") }
                    }
                }

                Text("Default Voting Deadline", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(24, 48, 72, 168).forEach { hrs ->
                        FilterChip(
                            selected = deadlineHours == hrs,
                            onClick = { if (isAdmin) deadlineHours = hrs },
                            label = { Text("${hrs}h") },
                            enabled = isAdmin
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Proposal Creator Can Vote", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = allowCreatorVote,
                        onCheckedChange = { if (isAdmin) allowCreatorVote = it },
                        enabled = isAdmin
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow Vote Change Before Deadline", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = allowVoteChange,
                        onCheckedChange = { if (isAdmin) allowVoteChange = it },
                        enabled = isAdmin
                    )
                }

                HorizontalDivider()

                Text("Eligible Voter Rule", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                EligibleVoterRule.entries.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isAdmin) { eligibleRule = rule },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = eligibleRule == rule,
                            onClick = { if (isAdmin) eligibleRule = rule },
                            enabled = isAdmin
                        )
                        Text(rule.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (eligibleRule == EligibleVoterRule.SPECIFIC_MEMBERS) {
                    activeMembers.forEach { member ->
                        val checked = member.userId in specificUids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isAdmin) {
                                    specificUids = if (checked) specificUids - member.userId else specificUids + member.userId
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (isAdmin) {
                                        specificUids = if (checked) specificUids - member.userId else specificUids + member.userId
                                    }
                                },
                                enabled = isAdmin
                            )
                            Text("${member.displayName} (${member.role})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { if (isAdmin) thresholdText = it },
                    enabled = isAdmin,
                    label = { Text("Large Expense Threshold (${activeFamily.currencyCode})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Categories Requiring Family Approval", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DecisionCategory.entries.forEach { cat ->
                        val selected = cat in requiredCategories
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (isAdmin) {
                                    requiredCategories = if (selected && requiredCategories.size > 1) {
                                        requiredCategories - cat
                                    } else {
                                        requiredCategories + cat
                                    }
                                }
                            },
                            enabled = isAdmin,
                            leadingIcon = if (selected) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(cat.shortLabel) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isAdmin) {
                Button(
                    onClick = {
                        val thresholdMinor = MoneyCalculator.parseDecimalToMinorUnits(
                            rawInput = thresholdText,
                            currencyCode = activeFamily.currencyCode,
                            allowZero = true
                        ).getOrDefault(500_000L)
                        val effectivePct = when (ruleMode) {
                            ApprovalRuleMode.UNANIMOUS -> 100
                            ApprovalRuleMode.MAJORITY -> 51
                            ApprovalRuleMode.MIN_PERCENTAGE -> minPctFloat.roundToInt()
                        }
                        onSaveRules(
                            ruleMode,
                            effectivePct,
                            minQuorum.coerceIn(1, maxMembers),
                            deadlineHours,
                            allowCreatorVote,
                            allowVoteChange,
                            eligibleRule,
                            specificUids.toList(),
                            requiredCategories,
                            thresholdMinor
                        )
                    },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("save_approval_rules_button")
                ) {
                    Text("Save Rules")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isAdmin) "Cancel" else "Close")
            }
        }
    )
}
