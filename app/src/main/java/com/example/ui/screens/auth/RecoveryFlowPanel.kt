package com.example.ui.screens.auth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.firebase.AuthService
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.AuthValidation
import com.example.core.model.PhoneOtpState
import com.example.core.model.RecoveryStep
import kotlinx.coroutines.launch

@Composable
fun RecoveryFlowPanel(
    authService: AuthService,
    prefsStore: ThemePreferencesStore,
    onHoldAuthNavigation: (Boolean) -> Unit,
    onRecoverySuccess: () -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val otpState by authService.otpState.collectAsStateWithLifecycle()

    var step by remember { mutableStateOf(RecoveryStep.ENTER_MOBILE) }
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var passkeyReenrolled by remember { mutableStateOf(false) }
    var priorSessionsRevoked by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(otpState) {
        when (val state = otpState) {
            is PhoneOtpState.Sending -> {
                isBusy = true
                errorMessage = null
            }
            is PhoneOtpState.CodeSent -> {
                isBusy = false
                errorMessage = null
                if (step == RecoveryStep.ENTER_MOBILE) {
                    step = RecoveryStep.VERIFY_OTP
                }
            }
            is PhoneOtpState.Verified -> {
                isBusy = false
                errorMessage = null
                if (step == RecoveryStep.ENTER_MOBILE || step == RecoveryStep.VERIFY_OTP) {
                    onHoldAuthNavigation(true)
                    step = RecoveryStep.COMPLETED
                }
            }
            is PhoneOtpState.Error -> {
                isBusy = false
                errorMessage = state.message
            }
            PhoneOtpState.Idle -> {
                isBusy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StepProgressHeader(
            currentStep = step.stepNumber,
            totalSteps = 3,
            stepTitle = step.title
        )

        AuthErrorBanner(message = errorMessage)

        when (step) {
            RecoveryStep.ENTER_MOBILE -> {
                Text(
                    text = "Recover Account or Replace Passkey",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Verify ownership of your registered mobile number (Member ID) via SMS OTP to regain access, enroll a new Passkey, and optionally revoke prior device sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it; errorMessage = null },
                    label = { Text("Registered Mobile Number (Member ID)") },
                    placeholder = { Text("+8801712345678") },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recovery_phone_input")
                )

                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) {
                            errorMessage = "Activity context required for SMS OTP recovery."
                            return@Button
                        }
                        val normalized = AuthValidation.normalizeE164Phone(phoneNumber)
                        if (!AuthValidation.isValidE164Phone(normalized)) {
                            errorMessage = "Enter a valid international E.164 mobile number."
                            return@Button
                        }
                        phoneNumber = normalized
                        onHoldAuthNavigation(true)
                        scope.launch {
                            authService.sendSmsOtp(activity, prefsStore, normalized, isResend = false)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("recovery_send_otp_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Send Recovery OTP")
                }
            }

            RecoveryStep.VERIFY_OTP -> {
                OtpVerificationPanel(
                    phoneNumber = phoneNumber,
                    otpState = otpState,
                    otpCode = otpCode,
                    onOtpCodeChange = { otpCode = it; errorMessage = null },
                    isBusy = isBusy,
                    onVerifyOtp = { vid, code ->
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            val res = authService.verifySmsOtpCode(vid, code, phoneNumber, prefsStore)
                            isBusy = false
                            res.onFailure { err -> errorMessage = err.message }
                        }
                    },
                    onResendOtp = {
                        val activity = context as? Activity ?: return@OtpVerificationPanel
                        scope.launch {
                            authService.sendSmsOtp(activity, prefsStore, phoneNumber, isResend = true)
                        }
                    }
                )
            }

            RecoveryStep.COMPLETED -> {
                Text(
                    text = "Mobile Ownership Verified",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Member ID $phoneNumber is unlocked. You can enroll a replacement Passkey for this device or revoke older sessions before entering your workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity ?: return@OutlinedButton
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            authService.enrollPasskey(activity, credentialManager, "Recovered Device Passkey")
                                .onSuccess { passkeyReenrolled = true }
                                .onFailure { err -> errorMessage = err.message ?: "Passkey enrollment failed." }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy && !passkeyReenrolled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("recovery_enroll_passkey_button")
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (passkeyReenrolled) "Replacement Passkey Registered" else "Register New Passkey")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            val keepId = prefsStore.getCurrentSessionId()
                            authService.functionsClient.revokeAllSessions(keepId)
                                .onSuccess { priorSessionsRevoked = true }
                                .onFailure { err -> errorMessage = err.message ?: "Unable to revoke prior sessions." }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy && !priorSessionsRevoked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("recovery_revoke_sessions_button")
                ) {
                    Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (priorSessionsRevoked) "Prior Sessions Revoked" else "Revoke Prior Device Sessions")
                }

                Button(
                    onClick = {
                        onHoldAuthNavigation(false)
                        onRecoverySuccess()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("recovery_continue_button")
                ) {
                    Text("Continue to Workspace")
                }
            }
        }

        if (step != RecoveryStep.COMPLETED) {
            TextButton(
                onClick = {
                    onHoldAuthNavigation(false)
                    authService.phoneOtpService.resetState()
                    onBackToLogin()
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Back to Sign In")
            }
        }
    }
}
