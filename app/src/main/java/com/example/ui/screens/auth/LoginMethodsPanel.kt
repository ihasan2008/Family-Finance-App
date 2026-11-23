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
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.firebase.AuthService
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.AuthLoginMethod
import com.example.core.model.AuthValidation
import com.example.core.model.PhoneOtpState
import kotlinx.coroutines.launch

@Composable
fun LoginMethodsPanel(
    authService: AuthService,
    credentialManager: CredentialManager,
    prefsStore: ThemePreferencesStore,
    onHoldAuthNavigation: (Boolean) -> Unit,
    onAuthSuccess: () -> Unit,
    onOpenRegistration: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenAdminLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val otpState by authService.otpState.collectAsStateWithLifecycle()

    var selectedMethod by remember { mutableStateOf(AuthLoginMethod.PHONE_OTP) }
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val lastPhone = prefsStore.getLastPhoneNumber()
        if (lastPhone.isNotBlank() && phoneNumber.isBlank()) {
            phoneNumber = lastPhone
        }
    }

    LaunchedEffect(otpState, selectedMethod) {
        when (val state = otpState) {
            is PhoneOtpState.Sending -> {
                isBusy = true
                errorMessage = null
            }
            is PhoneOtpState.CodeSent -> {
                isBusy = false
                errorMessage = null
            }
            is PhoneOtpState.Verified -> {
                if (selectedMethod == AuthLoginMethod.PHONE_OTP) {
                    isBusy = false
                    onHoldAuthNavigation(false)
                    onAuthSuccess()
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
        Text(
            text = "Passwordless Sign In",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Sign in with your verified mobile SMS OTP, hardware Passkey, or Google Account — no account password required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple(AuthLoginMethod.PHONE_OTP, "SMS OTP", Icons.Outlined.Sms),
                Triple(AuthLoginMethod.PASSKEY, "Passkey", Icons.Outlined.Fingerprint)
            ).forEach { (method, label, icon) ->
                FilterChip(
                    selected = selectedMethod == method,
                    onClick = {
                        selectedMethod = method
                        errorMessage = null
                    },
                    label = { Text(label) },
                    leadingIcon = {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("login_method_${method.name.lowercase()}")
                )
            }
        }

        AuthErrorBanner(message = errorMessage)

        when (selectedMethod) {
            AuthLoginMethod.PHONE_OTP -> {
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
                        .testTag("otp_login_phone_input")
                )

                if (otpState !is PhoneOtpState.CodeSent) {
                    Button(
                        onClick = {
                            val activity = context as? Activity ?: return@Button
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
                            .testTag("otp_login_send_button")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Send Verification Code")
                    }
                } else {
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
                                authService.verifySmsOtpCode(vid, code, phoneNumber, prefsStore)
                                    .onFailure { err ->
                                        isBusy = false
                                        errorMessage = err.message
                                    }
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
            }

            AuthLoginMethod.PASSKEY -> {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "WebAuthn / FIDO2 authentication using device biometrics, Face Recognition, or device PIN. Private keys never leave your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it; errorMessage = null },
                    label = { Text("Mobile Number / Member ID Hint (Optional)") },
                    placeholder = { Text("+8801712345678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("passkey_phone_hint_input")
                )

                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) {
                            errorMessage = "Activity context required for Credential Manager Passkey prompt."
                            return@Button
                        }
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            val res = authService.signInWithPasskey(
                                activity = activity,
                                credentialManager = credentialManager,
                                phoneNumberHint = phoneNumber.takeIf { it.isNotBlank() },
                                prefsStore = prefsStore
                            )
                            isBusy = false
                            res.onSuccess { onAuthSuccess() }
                                .onFailure { err -> errorMessage = err.message ?: "Passkey sign-in failed." }
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("passkey_sign_in_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.auth_passkey_cta))
                }
            }

            AuthLoginMethod.GOOGLE, AuthLoginMethod.FAMILY_ADMIN -> Unit
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedButton(
            onClick = {
                errorMessage = null
                isBusy = true
                authService.signInWithGoogle(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = {
                        isBusy = false
                        onAuthSuccess()
                    },
                    onAuthError = { msg ->
                        isBusy = false
                        errorMessage = msg
                    },
                    scope = scope,
                    onAuthCancelled = {
                        isBusy = false
                    }
                )
            },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("google_sign_in_button"),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.sign_in_with_google))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onOpenRecovery,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("open_recovery_button")
            ) {
                Text("Recover / New Passkey")
            }
            TextButton(
                onClick = onOpenAdminLogin,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("open_admin_login_button")
            ) {
                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Admin Login")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(
                onClick = onOpenRegistration,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("open_registration_button")
            ) {
                Text("New to Family Finance? Create Account")
            }
        }
    }
}
