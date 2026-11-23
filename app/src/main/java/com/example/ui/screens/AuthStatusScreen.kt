package com.example.ui.screens

import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.credentials.CredentialManager
import com.example.BuildConfig
import com.example.R
import com.example.core.local.LocalUserProfileEntity
import com.example.core.model.AccountStatus
import com.example.core.model.AuthValidation
import com.example.core.model.DeviceSessionDoc
import com.example.core.model.PasskeyCredentialDoc
import com.example.core.model.PhoneOtpState
import com.example.ui.screens.auth.IdentityMetadataChip
import com.example.ui.screens.auth.OtpVerificationPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AuthStatusScreen(
    currentUserId: String,
    userDisplayName: String,
    userEmail: String,
    userProfile: LocalUserProfileEntity?,
    passkeys: List<PasskeyCredentialDoc>,
    sessions: List<DeviceSessionDoc>,
    currentSessionId: String?,
    otpState: PhoneOtpState,
    isBusy: Boolean,
    onEnrollPasskey: (Activity, CredentialManager, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onRevokeSession: (String) -> Unit,
    onRevokeAllOtherSessions: () -> Unit,
    onToggleNewDeviceCheck: (Boolean) -> Unit,
    onSendMobileChangeOtp: (Activity, String, Boolean) -> Unit,
    onVerifyMobileChangeOtp: (String, String, String, Boolean) -> Unit,
    onResetMobileOtpState: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var passkeyLabel by remember { mutableStateOf("") }
    var showDob by remember { mutableStateOf(false) }
    var showChangeMobileDialog by remember { mutableStateOf(false) }

    val registeredDate = remember(userProfile?.registeredAtMillis) {
        val millis = userProfile?.registeredAtMillis ?: 0L
        if (millis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
        } else "Synced on first login"
    }

    val effectiveMemberId = userProfile?.memberId?.takeIf { it.isNotBlank() }
        ?: userProfile?.phoneNumber?.takeIf { it.isNotBlank() }
        ?: "Pending mobile verification"

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 860.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Account Identity, Member ID & Private Date of Birth
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Account Identity", style = MaterialTheme.typography.titleLarge)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = AccountStatus.fromRaw(userProfile?.accountStatus).label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    IdentityMetadataChip("Member ID (Verified Mobile)", effectiveMemberId)
                    IdentityMetadataChip("Display Name", userProfile?.displayName ?: userDisplayName)
                    IdentityMetadataChip(
                        "Family Display Mask",
                        userProfile?.maskedPhone ?: AuthValidation.maskPhoneNumber(effectiveMemberId)
                    )
                    IdentityMetadataChip("Firebase Auth UID", currentUserId.take(14) + "…")
                    IdentityMetadataChip("Contact / Email", userProfile?.email ?: userEmail)

                    // Private Encrypted Date of Birth Row
                    val dobText = userProfile?.dateOfBirth?.takeIf { it.isNotBlank() }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Private Date of Birth",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when {
                                        dobText == null -> "Not set"
                                        showDob -> dobText
                                        else -> "••••-••-•• (Encrypted)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (dobText != null) {
                                IconButton(
                                    onClick = { showDob = !showDob },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("toggle_dob_visibility_button")
                                ) {
                                    Icon(
                                        imageVector = if (showDob) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "Reveal or hide private date of birth"
                                    )
                                }
                            }
                        }
                    }

                    IdentityMetadataChip("Registration Date", registeredDate)
                    IdentityMetadataChip("Account Auth", "Passwordless (SMS OTP / Passkey)")

                    OutlinedButton(
                        onClick = {
                            onResetMobileOtpState()
                            showChangeMobileDialog = true
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("open_change_mobile_dialog_button")
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Verified Mobile Number")
                    }
                }
            }

            // 2. WebAuthn / FIDO2 Passkey Enrollment
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Passkey Enrollment (WebAuthn / FIDO2)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Enroll a hardware-backed passkey using biometrics, Face Recognition, or device PIN. Relying Party: ${BuildConfig.WEBAUTHN_RP_ID}. Private keys never leave your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = passkeyLabel,
                        onValueChange = { passkeyLabel = it.take(60) },
                        label = { Text("Passkey Label (e.g. Pixel Biometric)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("enroll_passkey_label_input")
                    )

                    Button(
                        onClick = {
                            val activity = context as? Activity ?: return@Button
                            onEnrollPasskey(activity, credentialManager, passkeyLabel.trim())
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("enroll_passkey_button")
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.auth_enroll_passkey))
                    }

                    if (passkeys.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        passkeys.forEach { pk ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pk.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "ID: ${pk.credentialId.take(14)}... • Backed up: ${if (pk.backedUp) "Yes" else "Device only"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeletePasskey(pk.credentialId) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Remove passkey",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. New-Device Verification & Session Revocation
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("New-Device SMS Verification", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Require an SMS OTP challenge when logging in from an unrecognized device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = userProfile?.newDeviceCheckEnabled ?: true,
                            onCheckedChange = onToggleNewDeviceCheck,
                            modifier = Modifier.testTag("new_device_verification_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Active & Recent Sessions (${sessions.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = onRevokeAllOtherSessions,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("revoke_all_sessions_button")
                        ) {
                            Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Revoke Others")
                        }
                    }

                    if (sessions.isEmpty()) {
                        Text(
                            text = "No session records synchronized yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        sessions.forEach { session ->
                            val isCurrent = session.sessionId == currentSessionId
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (session.revoked) {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = buildString {
                                                append(session.deviceLabel)
                                                if (isCurrent) append(" (This Device)")
                                            },
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "${session.authMethod} • ${if (session.revoked) "REVOKED" else "ACTIVE"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (session.revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (!session.revoked && !isCurrent) {
                                        TextButton(
                                            onClick = { onRevokeSession(session.sessionId) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("Revoke")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChangeMobileDialog) {
        var newPhone by remember { mutableStateOf("") }
        var otpCode by remember { mutableStateOf("") }
        var revokeOthers by remember { mutableStateOf(true) }
        var validationError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(otpState) {
            if (otpState is PhoneOtpState.Idle && showChangeMobileDialog && otpCode.length == 6) {
                showChangeMobileDialog = false
            }
        }

        AlertDialog(
            onDismissRequest = {
                onResetMobileOtpState()
                showChangeMobileDialog = false
            },
            title = { Text("Update Verified Mobile & Member ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Verify your new mobile number via SMS OTP. Your Member ID will update everywhere automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newPhone,
                        onValueChange = { newPhone = it; validationError = null },
                        label = { Text("New Mobile Number (E.164)") },
                        placeholder = { Text("+8801712345679") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("change_mobile_phone_input")
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = revokeOthers, onCheckedChange = { revokeOthers = it })
                        Text("Revoke other device sessions after update", style = MaterialTheme.typography.bodySmall)
                    }
                    if (otpState is PhoneOtpState.CodeSent) {
                        OtpVerificationPanel(
                            phoneNumber = newPhone,
                            otpState = otpState,
                            otpCode = otpCode,
                            onOtpCodeChange = { otpCode = it; validationError = null },
                            isBusy = isBusy,
                            onVerifyOtp = { vid, code ->
                                onVerifyMobileChangeOtp(vid, code, newPhone, revokeOthers)
                            },
                            onResendOtp = {
                                val activity = context as? Activity ?: return@OtpVerificationPanel
                                onSendMobileChangeOtp(activity, newPhone, true)
                            }
                        )
                    }
                    if (otpState is PhoneOtpState.Error) {
                        Text(
                            text = otpState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                if (otpState !is PhoneOtpState.CodeSent) {
                    Button(
                        onClick = {
                            val activity = context as? Activity
                            if (activity == null) {
                                validationError = "Activity context required for SMS OTP."
                                return@Button
                            }
                            val normalized = AuthValidation.normalizeE164Phone(newPhone)
                            if (!AuthValidation.isValidE164Phone(normalized)) {
                                validationError = "Enter a valid international E.164 mobile number."
                                return@Button
                            }
                            newPhone = normalized
                            onSendMobileChangeOtp(activity, normalized, false)
                        },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("send_change_mobile_otp_button")
                    ) {
                        Text("Send SMS OTP")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onResetMobileOtpState()
                    showChangeMobileDialog = false
                }) {
                    Text("Close")
                }
            }
        )
    }
}
