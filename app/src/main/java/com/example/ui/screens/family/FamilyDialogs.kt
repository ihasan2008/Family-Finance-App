package com.example.ui.screens.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.model.AuthValidation
import com.example.core.model.ChatRetentionOption
import com.example.core.model.CreateInvitationResponse
import com.example.core.model.FamilyRole
import com.example.core.model.FamilySecurityHelper
import com.example.ui.screens.auth.PasswordCreationFields
import com.example.ui.theme.MoneySmallStyle
import kotlin.math.roundToLong

@Composable
fun CreateFamilyDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, currencyCode: String, description: String?, familyPassword: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var description by remember { mutableStateOf("") }
    var familyPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Family Workspace") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Only verified members can create a family. A unique 12-character Family ID will be generated, assigning you as Single Primary Admin and protecting the workspace with your Family Password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validationError = null },
                    label = { Text("Family Name") },
                    placeholder = { Text("e.g. Rahman Household") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_name_input")
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(6) },
                    label = { Text("Primary Currency Code") },
                    placeholder = { Text("BDT or USD") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_currency_input")
                )
                CurrencyPresetRow(
                    selectedCode = currency,
                    onSelectCode = { currency = it }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    label = { Text("Household Description (Optional)") },
                    placeholder = { Text("Shared monthly budget & goals") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Family Password (Required for Family Admin Login & Join Requests)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                PasswordCreationFields(
                    password = familyPassword,
                    onPasswordChange = { familyPassword = it; validationError = null },
                    confirmPassword = confirmPassword,
                    onConfirmPasswordChange = { confirmPassword = it; validationError = null },
                    passwordLabel = "Family Password",
                    confirmLabel = "Confirm Family Password"
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    val strength = AuthValidation.evaluateFamilyPasswordStrength(familyPassword)
                    when {
                        trimmed.length < 2 -> {
                            validationError = "Please enter at least 2 characters for the family name."
                        }
                        !strength.isAcceptable -> {
                            validationError = "Family Password must be 8+ chars with upper, lower, digit & symbol."
                        }
                        familyPassword != confirmPassword -> {
                            validationError = "Family Passwords do not match."
                        }
                        else -> onConfirm(
                            trimmed,
                            currency.ifBlank { "USD" },
                            description.trim().ifBlank { null },
                            familyPassword
                        )
                    }
                },
                modifier = Modifier.testTag("confirm_create_family")
            ) {
                Text("Create Family")
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
fun JoinViaTokenDialog(
    onDismiss: () -> Unit,
    onConfirmByPassword: (familyId: String, familyPassword: String, relationLabel: String) -> Unit,
    onConfirmByToken: (invitationToken: String, relationLabel: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var familyIdInput by remember { mutableStateOf("") }
    var familyPassword by remember { mutableStateOf("") }
    var showFamilyPassword by remember { mutableStateOf(false) }
    var invitationToken by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("Member") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Shared Family") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; validationError = null },
                        text = { Text("Family ID + Password") },
                        modifier = Modifier.testTag("join_tab_password")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; validationError = null },
                        text = { Text("Invite Token") },
                        modifier = Modifier.testTag("join_tab_token")
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (selectedTab == 0) {
                                "Enter the 12-character Family ID and Family Password. Your request enters PENDING APPROVAL until the Primary Admin approves you."
                            } else {
                                "Enter the expiring FFINV-... invitation token from your Primary Admin. Requests still require Admin approval."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = familyIdInput,
                        onValueChange = {
                            familyIdInput = FamilySecurityHelper.normalizeFamilyIdInput(it).take(12)
                            validationError = null
                        },
                        label = { Text("12-Character Family ID") },
                        placeholder = { Text("e.g. A1B2C3D4E5F6") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_family_id_input")
                    )
                    OutlinedTextField(
                        value = familyPassword,
                        onValueChange = { familyPassword = it; validationError = null },
                        label = { Text("Family Password") },
                        singleLine = true,
                        visualTransformation = if (showFamilyPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showFamilyPassword = !showFamilyPassword }) {
                                Icon(
                                    imageVector = if (showFamilyPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle family password visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_family_password_input")
                    )
                } else {
                    OutlinedTextField(
                        value = invitationToken,
                        onValueChange = {
                            invitationToken = it.uppercase().take(64)
                            validationError = null
                        },
                        label = { Text("Expiring Invitation Token") },
                        placeholder = { Text("FFINV-XXXXXX-XXXXXX-XXXXXX") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_invitation_token_input")
                    )
                }

                OutlinedTextField(
                    value = relation,
                    onValueChange = { relation = it.take(40) },
                    label = { Text("Your Relation / Label (e.g. Partner, Parent)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val relationClean = relation.trim().ifBlank { "Member" }
                    if (selectedTab == 0) {
                        val normalizedId = FamilySecurityHelper.normalizeFamilyIdInput(familyIdInput)
                        when {
                            !FamilySecurityHelper.isValidFamilyId12(normalizedId) -> {
                                validationError = "Enter a valid 12-character Family ID (A-Z, 0-9)."
                            }
                            familyPassword.length < 8 -> {
                                validationError = "Enter the valid Family Password (at least 8 characters)."
                            }
                            else -> onConfirmByPassword(normalizedId, familyPassword, relationClean)
                        }
                    } else {
                        val cleanToken = invitationToken.trim().uppercase()
                        when {
                            cleanToken.length < 12 || !cleanToken.startsWith("FFINV-") -> {
                                validationError = "Enter a valid FFINV-... invitation token."
                            }
                            else -> onConfirmByToken(cleanToken, relationClean)
                        }
                    }
                },
                modifier = Modifier.testTag("confirm_join_family")
            ) {
                Text("Request to Join")
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
fun ChangeFamilyPasswordDialog(
    familyName: String,
    onDismiss: () -> Unit,
    onConfirm: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Family Password") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Rotate the Family Password for \"$familyName\". Required for Family Admin login, ownership transfers, and password-based join requests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; validationError = null },
                    label = { Text("Current Family Password") },
                    singleLine = true,
                    visualTransformation = if (showCurrent) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showCurrent = !showCurrent }) {
                            Icon(
                                imageVector = if (showCurrent) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle current family password visibility"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_family_current_password_input")
                )
                PasswordCreationFields(
                    password = newPassword,
                    onPasswordChange = { newPassword = it; validationError = null },
                    confirmPassword = confirmNewPassword,
                    onConfirmPasswordChange = { confirmNewPassword = it; validationError = null },
                    passwordLabel = "New Family Password",
                    confirmLabel = "Confirm New Family Password"
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val strength = AuthValidation.evaluateFamilyPasswordStrength(newPassword)
                    when {
                        currentPassword.isBlank() -> validationError = "Enter the current Family Password."
                        !strength.isAcceptable -> validationError = "New Family Password must be 8+ chars with upper, lower, digit & symbol."
                        newPassword != confirmNewPassword -> validationError = "New Family Passwords do not match."
                        else -> onConfirm(currentPassword, newPassword)
                    }
                },
                modifier = Modifier.testTag("confirm_change_family_password")
            ) {
                Text("Update Password")
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
fun ContributeFromPersonalDialog(
    targetFamilyName: String,
    currencyCode: String,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, category: String, description: String) -> Unit
) {
    var amountInput by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Family Contribution") }
    var description by remember { mutableStateOf("Transfer from Personal Finance") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contribute from Personal Finance") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Personal and Family ledgers remain strictly isolated. This explicit action logs an expense in your private Personal Finance workspace and an incoming contribution to \"$targetFamilyName\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it; validationError = null },
                    label = { Text("Amount ($currencyCode)") },
                    placeholder = { Text("150.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contribute_amount_input")
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it.take(50); validationError = null },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contribute_category_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(160); validationError = null },
                    label = { Text("Note / Description") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contribute_description_input")
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = amountInput.trim().toDoubleOrNull()
                    val amountMinor = if (parsed != null && parsed > 0.0) (parsed * 100.0).roundToLong() else 0L
                    when {
                        amountMinor <= 0L -> validationError = "Enter a valid positive amount."
                        category.isBlank() -> validationError = "Category is required."
                        else -> onConfirm(
                            amountMinor,
                            category.trim(),
                            description.trim().ifBlank { "Personal contribution to $targetFamilyName" }
                        )
                    }
                },
                modifier = Modifier.testTag("confirm_contribute_personal")
            ) {
                Text("Confirm Contribution")
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
fun CreateInvitationDialog(
    familyName: String,
    onDismiss: () -> Unit,
    onConfirm: (targetRole: FamilyRole, inviteeHint: String?, expiresInHours: Int, maxUses: Int) -> Unit
) {
    var inviteeHint by remember { mutableStateOf("") }
    var expiresInHours by remember { mutableIntStateOf(48) }
    var maxUses by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate Family Invitation") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Create a high-entropy, expiring invitation token for \"$familyName\". New invitees join as Members pending Primary Admin approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = inviteeHint,
                    onValueChange = { inviteeHint = it.take(80) },
                    label = { Text("Invitee Note / Label (Optional)") },
                    placeholder = { Text("e.g. Spouse or Parent invite") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(text = "Token Expiration", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24 to "24 Hours", 48 to "48 Hours", 168 to "7 Days").forEach { (hrs, label) ->
                        FilterChip(
                            selected = expiresInHours == hrs,
                            onClick = { expiresInHours = hrs },
                            label = { Text(label) }
                        )
                    }
                }
                Text(text = "Maximum Uses", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5).forEach { count ->
                        FilterChip(
                            selected = maxUses == count,
                            onClick = { maxUses = count },
                            label = { Text(if (count == 1) "Single Use" else "$count Uses") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(FamilyRole.MEMBER, inviteeHint.trim().ifBlank { null }, expiresInHours, maxUses)
                },
                modifier = Modifier.testTag("confirm_create_invitation")
            ) {
                Text("Generate Token")
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
fun CreatedInvitationTokenDialog(
    invitation: CreateInvitationResponse,
    onCopyToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitation Token Ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Share this secure invitation token with your family member. When they submit it, you will receive an approval prompt in Member Approvals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = invitation.token,
                        style = MoneySmallStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    )
                }
                Button(
                    onClick = { onCopyToken(invitation.token) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("copy_generated_invitation_token")
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Invitation Token")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilySettingsDialog(
    family: LocalFamilyEntity,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        currencyCode: String,
        description: String?,
        memberChatEnabled: Boolean,
        membersCanAddExpenses: Boolean,
        budgetStartDay: Int,
        chatRetentionDays: Int
    ) -> Unit
) {
    var name by remember(family.familyId) { mutableStateOf(family.name) }
    var currency by remember(family.familyId) { mutableStateOf(family.currencyCode) }
    var description by remember(family.familyId) { mutableStateOf(family.description.orEmpty()) }
    var memberChatEnabled by remember(family.familyId) { mutableStateOf(family.memberChatEnabled) }
    var membersCanAddExpenses by remember(family.familyId) { mutableStateOf(family.membersCanAddExpenses) }
    var budgetStartDay by remember(family.familyId) { mutableIntStateOf(family.budgetStartDay) }
    var chatRetentionDays by remember(family.familyId) { mutableIntStateOf(family.chatRetentionDays) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Family Profile & Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorText = null },
                    label = { Text("Family Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(6) },
                    label = { Text("Currency Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                CurrencyPresetRow(
                    selectedCode = currency,
                    onSelectCode = { currency = it }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    label = { Text("Description") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Members Create Records", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Allow new members to record household income & expenses by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = membersCanAddExpenses, onCheckedChange = { membersCanAddExpenses = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Family Chat Access", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Enable family chat participation for approved members.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = memberChatEnabled, onCheckedChange = { memberChatEnabled = it })
                }
                Text("Chat Message Retention Policy", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChatRetentionOption.entries.forEach { option ->
                        FilterChip(
                            selected = chatRetentionDays == option.days,
                            onClick = { chatRetentionDays = option.days },
                            label = { Text(option.label) },
                            modifier = Modifier.testTag("retention_chip_${option.days}")
                        )
                    }
                }
                Text("Budget Cycle Start Day", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10, 15, 25).forEach { day ->
                        FilterChip(
                            selected = budgetStartDay == day,
                            onClick = { budgetStartDay = day },
                            label = { Text("Day $day") }
                        )
                    }
                }
                if (errorText != null) {
                    Text(errorText!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.length < 2) {
                        errorText = "Family name must be at least 2 characters."
                    } else {
                        onSave(
                            trimmed,
                            currency.ifBlank { "USD" },
                            description.trim().ifBlank { null },
                            memberChatEnabled,
                            membersCanAddExpenses,
                            budgetStartDay,
                            chatRetentionDays
                        )
                    }
                },
                modifier = Modifier.testTag("confirm_save_family_settings")
            ) {
                Text("Save Settings")
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
fun ManageMemberDialog(
    member: LocalFamilyMemberEntity,
    isTargetPrimaryOwner: Boolean,
    isCallerPrimaryOwner: Boolean,
    onDismiss: () -> Unit,
    onSaveRoleAndPermissions: (
        role: FamilyRole,
        canCreateRecords: Boolean,
        canViewSharedFinances: Boolean,
        canParticipateInChat: Boolean
    ) -> Unit,
    onToggleSuspendMember: (suspend: Boolean) -> Unit,
    onRequestRemoveMember: () -> Unit,
    onRequestTransferOwnership: () -> Unit
) {
    val effectiveRole = if (isTargetPrimaryOwner) FamilyRole.ADMIN else FamilyRole.MEMBER
    val isCurrentlySuspended = member.memberStatus.equals("SUSPENDED", ignoreCase = true)
    var canCreateRecords by remember(member.userId) { mutableStateOf(member.canCreateRecords) }
    var canViewShared by remember(member.userId) { mutableStateOf(member.canViewSharedFinances) }
    var canChat by remember(member.userId) { mutableStateOf(member.canParticipateInChat) }
    val memberIdDisplay = remember(member.memberId, member.maskedPhone, member.userId) {
        member.maskedPhone?.takeIf { it.isNotBlank() }
            ?: AuthValidation.maskPhoneNumber(member.memberId).takeIf { member.memberId.isNotBlank() }
            ?: member.userId.take(10)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage ${member.displayName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Member ID: $memberIdDisplay  •  Status: ${member.memberStatus}",
                    style = MoneySmallStyle,
                    color = if (isCurrentlySuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Family Role", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(if (isTargetPrimaryOwner) "Primary Admin" else "Member") }
                    )
                }
                Text(
                    text = if (isTargetPrimaryOwner) {
                        "Single Primary Admin policy: transfer Primary Admin ownership below to change admin."
                    } else {
                        "Single Primary Admin policy: members have configurable permissions; use Transfer Ownership below to promote."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Member Permissions", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Create financial records", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = canCreateRecords, onCheckedChange = { canCreateRecords = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("View shared finances", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = canViewShared, onCheckedChange = { canViewShared = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Participate in family chat", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = canChat, onCheckedChange = { canChat = it })
                }

                if (!isTargetPrimaryOwner && isCallerPrimaryOwner) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedButton(
                        onClick = { onToggleSuspendMember(!isCurrentlySuspended) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("suspend_member_button")
                    ) {
                        Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isCurrentlySuspended) "Reinstate Member Access" else "Suspend Member Access")
                    }
                    OutlinedButton(
                        onClick = onRequestTransferOwnership,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("transfer_ownership_button")
                    ) {
                        Text("Transfer Primary Admin to ${member.displayName}")
                    }
                    OutlinedButton(
                        onClick = onRequestRemoveMember,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("remove_member_button")
                    ) {
                        Text("Remove Member from Family")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveRoleAndPermissions(effectiveRole, canCreateRecords, canViewShared, canChat)
                },
                modifier = Modifier.testTag("save_member_role_button")
            ) {
                Text("Save Changes")
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
fun ConfirmOwnershipTransferDialog(
    familyName: String,
    targetMember: LocalFamilyMemberEntity,
    onDismiss: () -> Unit,
    onConfirm: (familyPassword: String) -> Unit
) {
    var familyPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val memberLabel = targetMember.maskedPhone?.takeIf { it.isNotBlank() }
        ?: AuthValidation.maskPhoneNumber(targetMember.memberId).takeIf { targetMember.memberId.isNotBlank() }
        ?: targetMember.userId.take(10)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = { Text("Transfer Primary Admin?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "You are transferring Single Primary Admin ownership of \"$familyName\" to ${targetMember.displayName} (Member ID: $memberLabel). Confirm with the current Family Password.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = familyPassword,
                    onValueChange = { familyPassword = it; errorText = null },
                    label = { Text("Current Family Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle family password visibility"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_family_password_input")
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (familyPassword.isBlank()) {
                        errorText = "Enter the Family Password to authorize transfer."
                    } else {
                        onConfirm(familyPassword)
                    }
                },
                modifier = Modifier.testTag("confirm_transfer_ownership")
            ) {
                Text("Transfer Ownership")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyPresetRow(
    selectedCode: String,
    onSelectCode: (String) -> Unit
) {
    val presets = listOf(
        "BDT" to "BDT (৳)",
        "USD" to "USD ($)",
        "EUR" to "EUR (€)",
        "GBP" to "GBP (£)",
        "INR" to "INR (₹)"
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { (code, label) ->
            FilterChip(
                selected = selectedCode.equals(code, ignoreCase = true),
                onClick = { onSelectCode(code) },
                label = { Text(label) },
                modifier = Modifier.testTag("currency_preset_$code")
            )
        }
    }
}
