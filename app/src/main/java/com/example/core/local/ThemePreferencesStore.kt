package com.example.core.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "family_finance_prefs")

class ThemePreferencesStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val activeFamilyKey = stringPreferencesKey("active_family_id")
    private val deviceIdKey = stringPreferencesKey("install_device_id")
    private val currentSessionIdKey = stringPreferencesKey("current_session_id")
    private val lastPhoneNumberKey = stringPreferencesKey("last_phone_number")
    private val conflictPolicyKey = stringPreferencesKey("sync_conflict_policy")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    private val fcmTokenKey = stringPreferencesKey("fcm_token")
    private val workspaceModeKey = stringPreferencesKey("workspace_mode")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromRaw(prefs[themeKey])
    }

    val activeFamilyId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[activeFamilyKey]?.takeIf { it.isNotBlank() }
    }

    val workspaceMode: Flow<com.example.core.model.WorkspaceMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[workspaceModeKey]
        com.example.core.model.WorkspaceMode.entries.firstOrNull { it.name == raw }
            ?: com.example.core.model.WorkspaceMode.FAMILY
    }

    val currentSessionId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[currentSessionIdKey]?.takeIf { it.isNotBlank() }
    }

    val conflictPolicy: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[conflictPolicyKey]?.takeIf { it.isNotBlank() } ?: "MANUAL_REVIEW"
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[notificationsEnabledKey] ?: true
    }

    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.first()[deviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        context.dataStore.edit { prefs ->
            prefs[deviceIdKey] = generated
        }
        return generated
    }

    suspend fun getCurrentSessionId(): String? {
        return context.dataStore.data.first()[currentSessionIdKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setCurrentSessionId(sessionId: String?) {
        context.dataStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) {
                prefs.remove(currentSessionIdKey)
            } else {
                prefs[currentSessionIdKey] = sessionId
            }
        }
    }

    suspend fun getLastPhoneNumber(): String {
        return context.dataStore.data.first()[lastPhoneNumberKey].orEmpty()
    }

    suspend fun setLastPhoneNumber(phone: String) {
        context.dataStore.edit { prefs ->
            prefs[lastPhoneNumberKey] = phone
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }

    suspend fun setActiveFamilyId(familyId: String?) {
        context.dataStore.edit { prefs ->
            if (familyId.isNullOrBlank()) {
                prefs.remove(activeFamilyKey)
            } else {
                prefs[activeFamilyKey] = familyId
            }
        }
    }

    suspend fun setWorkspaceMode(mode: com.example.core.model.WorkspaceMode) {
        context.dataStore.edit { prefs ->
            prefs[workspaceModeKey] = mode.name
        }
    }

    suspend fun setConflictPolicy(policyName: String) {
        context.dataStore.edit { prefs ->
            prefs[conflictPolicyKey] = policyName
        }
    }

    suspend fun isNotificationsEnabled(): Boolean {
        return context.dataStore.data.first()[notificationsEnabledKey] ?: true
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[notificationsEnabledKey] = enabled
        }
    }

    suspend fun getCachedFcmToken(): String? {
        return context.dataStore.data.first()[fcmTokenKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setCachedFcmToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(fcmTokenKey)
            } else {
                prefs[fcmTokenKey] = token
            }
        }
    }
}
