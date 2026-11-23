package com.example.ui.screens.auth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
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
import com.example.core.model.RegistrationStep
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RegistrationFlowPanel(
    authService: AuthService,
    prefsStore: ThemePreferencesStore,
    onHoldAuthNavigation: (Boolean) -> Unit,
    onContinueToFamilySetup: () -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val otpState by authService.otpState.collectAsStateWithLifecycle()

    var step by remember { mutableStateOf(RegistrationStep.ENTER_DETAILS) }
    var displayName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var createdMemberId by remember { mutableStateOf("") }
    var createdMaskedPhone by remember { mutableStateOf("") }
    var createdDateLabel by remember { mutableStateOf("") }
    var passkeyEnrolled by remember { mutableStateOf(false) }
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
                if (step == RegistrationStep.ENTER_DETAILS) {
                    step = RegistrationStep.VERIFY_OTP
                }
            }
            is PhoneOtpState.Verified -> {
                if (step == RegistrationStep.ENTER_DETAILS || step == RegistrationStep.VERIFY_OTP) {
                    onHoldAuthNavigation(true)
                    isBusy = true
                    errorMessage = null
                    val res = authService.completePasswordlessRegistration(
                        phoneNumber = phoneNumber.ifBlank { state.phoneNumber },
                        displayName = displayName,
                        dateOfBirthIso = dateOfBirth,
                        prefsStore = prefsStore
                    )
                    isBusy = false
                    res.onSuccess { resp ->
                        createdMemberId = resp.memberId.ifBlank { state.phoneNumber }
                        createdMaskedPhone = resp.maskedPhone.ifBlank {
                            AuthValidation.maskPhoneNumber(state.phoneNumber)
                        }
                        createdDateLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                        step = RegistrationStep.ACCOUNT_READY
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Unable to finalize passwordless account registration."
                    }
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
            RegistrationStep.ENTER_DETAILS -> {
                Text(
                    text = "Create Member Account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "No password required. Verify your mobile number via SMS OTP — your verified mobile number becomes your permanent Member ID. Your Date of Birth stays private and encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; errorMessage = null },
                    label = { Text("Full Name") },
                    placeholder = { Text("e.g. Ayesha Rahman") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_name_input")
                )

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it; errorMessage = null },
                    label = { Text("Mobile Number (E.164 — Member ID)") },
                    placeholder = { Text("+8801712345678") },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_phone_input")
                )

                OutlinedTextField(
                    value = dateOfBirth,
                    onValueChange = { dateOfBirth = it.take(10); errorMessage = null },
                    label = { Text("Date of Birth (Private • YYYY-MM-DD)") },
                    placeholder = { Text("1995-08-20") },
                    leadingIcon = { Icon(Icons.Outlined.Cake, contentDescription = null) },
                    supportingText = {
                        Text("Stored encrypted locally and private to your account — never shown to family members.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_dob_input")
                )

                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) {
                            errorMessage = "Activity context required for SMS OTP verification."
                            return@Button
                        }
                        if (displayName.trim().length < 2) {
                            errorMessage = "Enter your full name (at least 2 characters)."
                            return@Button
                        }
                        val normalizedPhone = AuthValidation.normalizeE164Phone(phoneNumber)
                        if (!AuthValidation.isValidE164Phone(normalizedPhone)) {
                            errorMessage = "Enter a valid international E.164 mobile number (e.g. +8801712345678)."
                            return@Button
                        }
                        val validDob = AuthValidation.validateDateOfBirthIso(dateOfBirth)
                        if (validDob == null) {
                            errorMessage = "Enter a valid Date of Birth in YYYY-MM-DD format (not in the future)."
                            return@Button
                        }
                        phoneNumber = normalizedPhone
                        dateOfBirth = validDob
                        onHoldAuthNavigation(true)
                        scope.launch {
                            authService.sendSmsOtp(activity, prefsStore, normalizedPhone, isResend = false)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("register_send_otp_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Verify Mobile via SMS OTP")
                }
            }

            RegistrationStep.VERIFY_OTP -> {
                OtpVerificationPanel(
                    phoneNumber = phoneNumber,
                    otpState = otpState,
                    otpCode = otpCode,
                    onOtpCodeChange = { otpCode = it; errorMessage = null },
                    isBusy = isBusy,
                    onVerifyOtp = { verificationId, code ->
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            val res = authService.verifySmsOtpCode(verificationId, code, phoneNumber, prefsStore)
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

            RegistrationStep.ACCOUNT_READY -> {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Passwordless Account Ready",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Your Personal Finance workspace is isolated and ready. Optionally enroll a Passkey for instant biometric sign-in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IdentityMetadataChip(
                    "Member ID (Verified Mobile)",
                    createdMemberId.ifBlank { phoneNumber }
                )
                IdentityMetadataChip("Family Display Mask", createdMaskedPhone.ifBlank { AuthValidation.maskPhoneNumber(phoneNumber) })
                IdentityMetadataChip("Account Status", "ACTIVE (Passwordless)")
                IdentityMetadataChip("Registration Date", createdDateLabel.ifBlank { "Just now" })

                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity ?: return@OutlinedButton
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            val res = authService.enrollPasskey(activity, credentialManager, "Primary Device Passkey")
                            isBusy = false
                            res.onSuccess {
                                passkeyEnrolled = true
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Passkey enrollment failed."
                            }
                        }
                    },
                    enabled = !isBusy && !passkeyEnrolled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("register_enroll_passkey_button")
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (passkeyEnrolled) "Passkey Enrolled" else "Enroll Passkey Now (Optional)")
                }

                Button(
                    onClick = {
                        onHoldAuthNavigation(false)
                        onContinueToFamilySetup()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("continue_to_family_setup_button")
                ) {
                    Icon(Icons.Outlined.Diversity3, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue to Workspace")
                }
            }
        }

        if (step != RegistrationStep.ACCOUNT_READY) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = {
                        onHoldAuthNavigation(false)
                        authService.phoneOtpService.resetState()
                        onBackToLogin()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Already have an account? Sign In")
                }
            }
        }
    }
}
