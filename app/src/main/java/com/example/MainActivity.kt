package com.example

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.firebase.ConnectionMonitor
import com.example.core.firebase.DatabaseInitializer
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.firebase.configureAppCheck
import com.example.core.local.FamilyFinanceDatabase
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.AppModule
import com.example.core.model.ThemeMode
import com.example.core.notifications.AndroidNotificationHelper
import com.example.core.remote.authStateFlow
import com.example.data.repository.FamilyFinanceRepository
import com.example.ui.navigation.AppRoute
import com.example.ui.navigation.FamilyFinanceShell
import com.example.ui.navigation.toRoute
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.FamilyFinanceTheme
import com.example.ui.viewmodel.MainViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingNotificationModule by mutableStateOf<String?>(null)
    private var pendingMeetInviteInput by mutableStateOf<String?>(null)
    private var isInActiveVideoCall by mutableStateOf(false)
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch Intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)
        handleIntentDeepLinks(intent)

        enableEdgeToEdge()
        setContent {
            FamilyFinanceApp(
                deepLinkModuleName = pendingNotificationModule,
                onDeepLinkConsumed = { pendingNotificationModule = null },
                prefilledMeetInviteInput = pendingMeetInviteInput,
                onPrefilledMeetInviteConsumed = { pendingMeetInviteInput = null },
                isInPipMode = isInPipModeState,
                onEnterPipMode = { enterVideoCallPip() },
                onActiveVideoCallChanged = { active -> isInActiveVideoCall = active }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentDeepLinks(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInActiveVideoCall) {
            enterVideoCallPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    private fun handleIntentDeepLinks(intent: Intent?) {
        val moduleName = intent?.getStringExtra(AndroidNotificationHelper.EXTRA_TARGET_MODULE)
        if (!moduleName.isNullOrBlank()) {
            pendingNotificationModule = moduleName
        }
        val uri = intent?.data
        if (uri != null && uri.scheme.equals("https", ignoreCase = true) && uri.path?.startsWith("/meet") == true) {
            pendingNotificationModule = AppModule.FAMILY_MEET.name
            pendingMeetInviteInput = intent.dataString
        }
    }

    private fun enterVideoCallPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInActiveVideoCall) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}

@Composable
fun FamilyFinanceApp(
    auth: FirebaseAuth = FirebaseServiceRegistry.auth,
    deepLinkModuleName: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    prefilledMeetInviteInput: String? = null,
    onPrefilledMeetInviteConsumed: () -> Unit = {},
    isInPipMode: Boolean = false,
    onEnterPipMode: () -> Unit = {},
    onActiveVideoCallChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsStore = remember(context) { ThemePreferencesStore(context.applicationContext) }
    val rootThemeMode by prefsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var holdNavigationDuringAuthFlow by remember { mutableStateOf(false) }
    var pendingStartRoute by remember { mutableStateOf<AppRoute>(AppRoute.Dashboard) }
    val user = currentUser

    val deepLinkRoute = remember(deepLinkModuleName) {
        deepLinkModuleName?.let { raw ->
            AppModule.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.toRoute()
                ?: AppRoute.Notifications
        }
    }

    FamilyFinanceTheme(themeMode = rootThemeMode) {
        if (user == null || holdNavigationDuringAuthFlow) {
            AuthScreen(
                themeMode = rootThemeMode,
                onToggleTheme = { nextMode ->
                    scope.launch { prefsStore.setThemeMode(nextMode) }
                },
                onHoldAuthNavigation = { hold ->
                    holdNavigationDuringAuthFlow = hold
                },
                onAuthSuccess = { openFamilySetup ->
                    pendingStartRoute = if (openFamilySetup) AppRoute.FamilyManagement else AppRoute.Dashboard
                    holdNavigationDuringAuthFlow = false
                }
            )
        } else {
            val uid = user.uid
            key(uid) {
                val mainViewModel: MainViewModel = viewModel(
                    key = uid,
                    factory = viewModelFactory {
                        initializer {
                            val app = checkNotNull(this[APPLICATION_KEY]) {
                                "APPLICATION_KEY missing from CreationExtras"
                            }
                            val firestore = FirebaseServiceRegistry.firestore(app)
                            val connectionMonitor = ConnectionMonitor(app)
                            val localDb = FamilyFinanceDatabase.getInstance(app)
                            val themeStore = ThemePreferencesStore(app)
                            val repository = FamilyFinanceRepository(
                                db = firestore,
                                localDb = localDb,
                                prefsStore = themeStore,
                                auth = auth,
                                connectionMonitor = connectionMonitor
                            )
                            val initializerUtil = DatabaseInitializer(
                                context = app,
                                repository = repository
                            )
                            val configSnapshot = FirebaseServiceRegistry.inspectConfiguration(app)
                            val notificationHelper = AndroidNotificationHelper(app).apply {
                                ensureChannelsCreated()
                            }
                            MainViewModel(
                                repository = repository,
                                connectionMonitor = connectionMonitor,
                                databaseInitializer = initializerUtil,
                                configSnapshot = configSnapshot,
                                currentUserId = uid,
                                firebaseUser = user,
                                notificationHelper = notificationHelper,
                                appContext = app
                            )
                        }
                    }
                )

                val displayLabel = user.displayName?.takeIf { it.isNotBlank() }
                    ?: user.phoneNumber?.takeIf { it.isNotBlank() }
                    ?: "Family Member"
                val contactLabel = user.email?.takeIf { it.isNotBlank() }
                    ?: user.phoneNumber?.takeIf { it.isNotBlank() }
                    ?: "Verified Session"

                FamilyFinanceShell(
                    currentUserId = uid,
                    userDisplayName = displayLabel,
                    userEmail = contactLabel,
                    viewModel = mainViewModel,
                    initialRoute = pendingStartRoute,
                    deepLinkRoute = deepLinkRoute,
                    onDeepLinkConsumed = onDeepLinkConsumed,
                    prefilledMeetInviteInput = prefilledMeetInviteInput,
                    onPrefilledMeetInviteConsumed = onPrefilledMeetInviteConsumed,
                    isInPipMode = isInPipMode,
                    onEnterPipMode = onEnterPipMode,
                    onActiveVideoCallChanged = onActiveVideoCallChanged,
                    onSignedOut = {
                        pendingStartRoute = AppRoute.Dashboard
                        holdNavigationDuringAuthFlow = false
                    }
                )
            }
        }
    }
}
