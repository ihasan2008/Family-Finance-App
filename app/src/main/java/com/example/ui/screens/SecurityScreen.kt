package com.example.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.firebase.AppCheckStatus
import com.example.core.firebase.FirebaseConfigSnapshot
import com.example.core.local.LocalFamilyEntity
import com.example.core.model.AppModule
import com.example.core.model.DatabaseHealthSnapshot
import com.example.ui.components.ModuleStatusBadge

@Composable
fun SecurityScreen(
    currentUserId: String,
    activeFamily: LocalFamilyEntity?,
    configSnapshot: FirebaseConfigSnapshot,
    appCheckStatus: AppCheckStatus,
    passkeyCount: Int,
    activeSessionCount: Int,
    newDeviceCheckEnabled: Boolean,
    databaseHealth: DatabaseHealthSnapshot = DatabaseHealthSnapshot(),
    onOpenAuthManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Security & Data Isolation",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        ModuleStatusBadge(status = AppModule.SECURITY.status)
                    }

                    SecurityCheckItem(
                        title = "Firebase App Check Attestation",
                        detail = "${appCheckStatus.label} (${configSnapshot.environment.displayName} environment). Protects backend APIs from unauthorized client requests."
                    )
                    SecurityCheckItem(
                        title = "Multi-Method Auth & Passkeys",
                        detail = "UID: $currentUserId • $passkeyCount enrolled FIDO2 passkey(s) • $activeSessionCount active session(s) • New-device SMS check ${if (newDeviceCheckEnabled) "ON" else "OFF"}."
                    )
                    SecurityCheckItem(
                        title = "Zero-Trust Credential Storage",
                        detail = "Raw passwords and private passkey keys are never stored in Firestore. Password hashes use salted scrypt inside server-only auth_credentials."
                    )
                    SecurityCheckItem(
                        title = "Zero-Trust Family Isolation",
                        detail = if (activeFamily != null) {
                            "Active workspace \"${activeFamily.name}\" (${activeFamily.familyId}) enforces server-side memberUids containment and strict key allowlists."
                        } else {
                            "Every family document and subcollection enforces server-side memberUids authorization and strict schema validation."
                        }
                    )
                    SecurityCheckItem(
                        title = "Offline Cache Encryption & Remote Wipe",
                        detail = "Sensitive local records, cached reports, and chat history use ${databaseHealth.encryptionEngineName}. ${databaseHealth.unresolvedConflictsCount} conflict(s) pending; local cache purges automatically on sign-out or session revocation."
                    )

                    Button(
                        onClick = onOpenAuthManagement,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("open_passkey_session_security_button")
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Manage Passkeys, Sessions & Recovery Controls")
                    }
                }
            }

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Enforced Server & Client Safeguards",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "• Sliding-window login & SMS OTP rate limiting\n• FIDO2/WebAuthn hardware-backed platform authenticator\n• Refresh token revocation across compromised devices\n• Rule-enforced immutable registration timestamps & account lock guards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityCheckItem(title: String, detail: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
