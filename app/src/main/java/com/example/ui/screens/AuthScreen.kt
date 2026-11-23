package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.firebase.AuthService
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.AuthScreenMode
import com.example.core.model.AuthValidation
import com.example.core.model.FamilySecurityHelper
import com.example.core.model.PhoneOtpState
import com.example.core.model.ThemeMode
import com.example.ui.components.FamilyFinanceBrandLockup
import com.example.ui.screens.auth.AuthErrorBanner
import com.example.ui.screens.auth.LoginMethodsPanel
import com.example.ui.screens.auth.OtpVerificationPanel
import com.example.ui.screens.auth.RecoveryFlowPanel
import com.example.ui.screens.auth.RegistrationFlowPanel
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    themeMode: ThemeMode,
    onToggleTheme: (ThemeMode) -> Unit,
    onHoldAuthNavigation: (Boolean) -> Unit,
    onAuthSuccess: (openFamilySetup: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val prefsStore = remember(context) { ThemePreferencesStore(context.applicationContext) }
    val authService = remember { AuthService() }

    var screenMode by remember { mutableStateOf(AuthScreenMode.WELCOME) }

    BackHandler(enabled = screenMode != AuthScreenMode.WELCOME) {
        onHoldAuthNavigation(false)
        authService.phoneOtpService.resetState()
        screenMode = AuthScreenMode.WELCOME
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .safeDrawingPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            val isWide = maxWidth >= 840.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (isWide) 1040.dp else 540.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FamilyFinanceBrandLockup()
                    IconButton(
                        onClick = {
                            val next = when (themeMode) {
                                ThemeMode.LIGHT -> ThemeMode.DARK
                                ThemeMode.DARK -> ThemeMode.LIGHT
                                ThemeMode.SYSTEM -> ThemeMode.DARK
                            }
                            onToggleTheme(next)
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("auth_theme_toggle")
                    ) {
                        Icon(
                            imageVector = if (themeMode == ThemeMode.DARK) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle theme",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val authCard: @Composable (Modifier) -> Unit = { cardModifier ->
                    Card(
                        modifier = cardModifier,
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(22.dp)) {
                            when (screenMode) {
                                AuthScreenMode.WELCOME -> {
                                    WelcomePanel(
                                        onSignIn = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.LOGIN
                                        },
                                        onCreateAccount = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.REGISTER
                                        },
                                        onAdminLogin = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.FAMILY_ADMIN_LOGIN
                                        }
                                    )
                                }

                                AuthScreenMode.LOGIN, AuthScreenMode.NEW_DEVICE_VERIFY -> {
                                    LoginMethodsPanel(
                                        authService = authService,
                                        credentialManager = credentialManager,
                                        prefsStore = prefsStore,
                                        onHoldAuthNavigation = onHoldAuthNavigation,
                                        onAuthSuccess = { onAuthSuccess(false) },
                                        onOpenRegistration = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.REGISTER
                                        },
                                        onOpenRecovery = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.RECOVERY_RESET
                                        },
                                        onOpenAdminLogin = {
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.FAMILY_ADMIN_LOGIN
                                        }
                                    )
                                }

                                AuthScreenMode.REGISTER -> {
                                    RegistrationFlowPanel(
                                        authService = authService,
                                        prefsStore = prefsStore,
                                        onHoldAuthNavigation = onHoldAuthNavigation,
                                        onContinueToFamilySetup = { onAuthSuccess(true) },
                                        onBackToLogin = { screenMode = AuthScreenMode.LOGIN }
                                    )
                                }

                                AuthScreenMode.RECOVERY_RESET -> {
                                    RecoveryFlowPanel(
                                        authService = authService,
                                        prefsStore = prefsStore,
                                        onHoldAuthNavigation = onHoldAuthNavigation,
                                        onRecoverySuccess = { onAuthSuccess(false) },
                                        onBackToLogin = { screenMode = AuthScreenMode.LOGIN }
                                    )
                                }

                                AuthScreenMode.FAMILY_ADMIN_LOGIN -> {
                                    FamilyAdminLoginPanel(
                                        authService = authService,
                                        credentialManager = credentialManager,
                                        prefsStore = prefsStore,
                                        onHoldAuthNavigation = onHoldAuthNavigation,
                                        onAdminUnlocked = { onAuthSuccess(true) },
                                        onBack = {
                                            onHoldAuthNavigation(false)
                                            authService.phoneOtpService.resetState()
                                            screenMode = AuthScreenMode.WELCOME
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        HeroArtworkCard(modifier = Modifier.weight(1f))
                        authCard(Modifier.weight(1.1f))
                    }
                } else {
                    HeroArtworkCard(modifier = Modifier.fillMaxWidth())
                    authCard(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WelcomePanel(
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onAdminLogin: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Passwordless personal and household finance with verified SMS OTP & hardware Passkeys, strict family data isolation, and instant workspace switching.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("welcome_sign_in_button")
        ) {
            Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign In")
        }

        OutlinedButton(
            onClick = onCreateAccount,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("welcome_create_account_button")
        ) {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create New Account")
        }

        TextButton(
            onClick = onAdminLogin,
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("welcome_admin_login_button")
        ) {
            Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Family Admin Login")
        }
    }
}

@Composable
private fun FamilyAdminLoginPanel(
    authService: AuthService,
    credentialManager: CredentialManager,
    prefsStore: ThemePreferencesStore,
    onHoldAuthNavigation: (Boolean) -> Unit,
    onAdminUnlocked: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val otpState by authService.otpState.collectAsStateWithLifecycle()

    var memberSignedIn by remember { mutableStateOf(authService.currentUser != null) }
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var familyIdInput by remember { mutableStateOf("") }
    var familyPassword by remember { mutableStateOf("") }
    var showFamilyPassword by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = "Family Admin Login",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "Step 1: Verify your personal identity (Passkey, SMS OTP, or Google). Step 2: Enter the 12-character Family ID and Family Password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AuthErrorBanner(message = errorMessage)

        if (!memberSignedIn && authService.currentUser == null) {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it; errorMessage = null },
                label = { Text("Admin Mobile Number (E.164)") },
                placeholder = { Text("+8801712345678") },
                leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_member_phone_input")
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
                        .height(48.dp)
                        .testTag("admin_send_otp_button")
                ) {
                    Text("Verify Admin Mobile via SMS OTP")
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
                                .onSuccess { memberSignedIn = true }
                                .onFailure { err -> errorMessage = err.message }
                            isBusy = false
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity ?: return@OutlinedButton
                        onHoldAuthNavigation(true)
                        scope.launch {
                            isBusy = true
                            errorMessage = null
                            authService.signInWithPasskey(activity, credentialManager, phoneNumber.takeIf { it.isNotBlank() }, prefsStore)
                                .onSuccess { memberSignedIn = true }
                                .onFailure { err ->
                                    onHoldAuthNavigation(false)
                                    errorMessage = err.message ?: "Passkey sign-in failed."
                                }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_passkey_button")
                ) {
                    Text("Use Passkey")
                }
                OutlinedButton(
                    onClick = {
                        onHoldAuthNavigation(true)
                        isBusy = true
                        errorMessage = null
                        authService.signInWithGoogle(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isBusy = false
                                memberSignedIn = true
                            },
                            onAuthError = { msg ->
                                isBusy = false
                                onHoldAuthNavigation(false)
                                errorMessage = msg
                            },
                            scope = scope,
                            onAuthCancelled = {
                                isBusy = false
                                onHoldAuthNavigation(false)
                            }
                        )
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_google_button")
                ) {
                    Text("Google")
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Text(
                    text = "Member identity verified (${authService.currentUser?.phoneNumber ?: authService.currentUser?.email ?: "Active Session"}). Enter your Family credentials below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }

            OutlinedTextField(
                value = familyIdInput,
                onValueChange = {
                    familyIdInput = FamilySecurityHelper.normalizeFamilyIdInput(it)
                    errorMessage = null
                },
                label = { Text("12-Character Family ID") },
                placeholder = { Text("A7K9M2Q4W8X3") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_login_family_id_input")
            )

            OutlinedTextField(
                value = familyPassword,
                onValueChange = { familyPassword = it; errorMessage = null },
                label = { Text("Family Password") },
                singleLine = true,
                visualTransformation = if (showFamilyPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showFamilyPassword = !showFamilyPassword }) {
                        Icon(
                            imageVector = if (showFamilyPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "Toggle Family Password visibility"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_login_password_input")
            )

            Button(
                onClick = {
                    val cleanFid = FamilySecurityHelper.normalizeFamilyIdInput(familyIdInput)
                    if (!FamilySecurityHelper.isValidFamilyId12(cleanFid)) {
                        errorMessage = "Enter a valid 12-character uppercase alphanumeric Family ID."
                        return@Button
                    }
                    if (familyPassword.isBlank()) {
                        errorMessage = "Enter the Family Password."
                        return@Button
                    }
                    scope.launch {
                        isBusy = true
                        errorMessage = null
                        val res = authService.verifyFamilyAdminLogin(cleanFid, familyPassword)
                        isBusy = false
                        res.onSuccess {
                            onHoldAuthNavigation(false)
                            onAdminUnlocked()
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Unable to verify Family Admin credentials."
                        }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("admin_login_submit_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Verify & Unlock Family Admin")
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("Back to Welcome")
        }
    }
}

@Composable
private fun HeroArtworkCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Image(
                painter = painterResource(id = R.drawable.img_family_growth_banner),
                contentDescription = stringResource(R.string.cd_brand_hero),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Plan • Track • Grow Together",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Passwordless zero-trust household security",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SecurityHighlightRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = "Passkeys & SMS OTP Authentication",
                    subtitle = "No member account passwords. Sign in with verified mobile OTP, hardware Passkeys, or Google."
                )
                SecurityHighlightRow(
                    icon = Icons.Outlined.Lock,
                    title = "Isolated Personal & Family Ledgers",
                    subtitle = "Start in private Personal Finance and connect to a 12-char Family ID with Admin approval."
                )
                SecurityHighlightRow(
                    icon = Icons.Outlined.CloudSync,
                    title = "Encrypted Device Storage & Controls",
                    subtitle = "Private Date of Birth & offline records protected via AES-256-GCM Keystore encryption."
                )
            }
        }
    }
}

@Composable
private fun SecurityHighlightRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(8.dp)
                    .size(18.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
