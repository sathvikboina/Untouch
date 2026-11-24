package com.untouch.app.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.untouch.app.receivers.UntouchDeviceAdminReceiver
import com.untouch.app.security.PinManager
import com.untouch.app.ui.theme.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UntouchTheme {
                val state by viewModel.uiState.collectAsState()
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    when (state.currentScreen) {
                        Screen.ONBOARDING -> OnboardingScreen(
                            onContinue = { viewModel.navigateTo(Screen.APP_SELECTION) }
                        )
                        Screen.APP_SELECTION -> AppSelectionScreen(
                            apps = state.installedApps,
                            selectedPackages = state.blockedPackages,
                            onToggle = { viewModel.togglePackageSelection(it) },
                            onDone = {
                                if (state.isOnboardingComplete) {
                                    viewModel.navigateTo(Screen.DASHBOARD)
                                } else {
                                    viewModel.navigateTo(Screen.PIN_SETUP)
                                }
                            }
                        )
                        Screen.PIN_SETUP -> PinSetupScreen(
                            onPinConfirmed = { viewModel.savePinAndFinishOnboarding(it) }
                        )
                        Screen.DASHBOARD -> DashboardScreen(
                            state = state,
                            onEnableStrict = { viewModel.navigateTo(Screen.STRICT_CONFIRM) },
                            onEditApps = { viewModel.requestAdminAction(AdminAction.EDIT_BLOCKED_APPS) },
                            onDisableStrict = { viewModel.requestAdminAction(AdminAction.DISABLE_STRICT_MODE) },
                            onOpenPermissions = {
                                if (state.isStrictModeActive) {
                                    viewModel.requestAdminAction(AdminAction.OPEN_PERMISSIONS)
                                } else {
                                    viewModel.navigateTo(Screen.PERMISSIONS)
                                }
                            },
                            onResetVault = { viewModel.resetCorruptedVault() }
                        )
                        Screen.STRICT_CONFIRM -> StrictModeConfirmScreen(
                            blockedCount = state.blockedPackages.size,
                            onConfirm = { viewModel.activateStrictMode() },
                            onCancel = { viewModel.navigateTo(Screen.DASHBOARD) }
                        )
                        Screen.PERMISSIONS -> PermissionsScreen(
                            state = state,
                            onRefresh = { viewModel.reloadAllState() },
                            onContinue = { viewModel.navigateTo(Screen.DASHBOARD) }
                        )
                        Screen.ADMIN_AUTH -> AdminAuthScreen(
                            error = state.authError,
                            onSubmit = { viewModel.submitAdminPin(it) },
                            onCancel = { viewModel.navigateTo(Screen.DASHBOARD) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadAllState()
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.padding(top = 48.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = AccentWhite, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("UNTOUCH", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Strict, offline-only app blocking governed by a trusted person's PIN.",
                color = TextSecondary, fontSize = 16.sp, lineHeight = 24.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            BulletItem("100% local & encrypted — no servers, no analytics.")
            BulletItem("No snooze, no temporary unlock, no easy recovery.")
            BulletItem("Ask a trusted person to enter the PIN during setup.")
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("BEGIN SETUP", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun BulletItem(text: String) {
    Text("•  $text", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun AppSelectionScreen(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onDone: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.appName.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("SELECT APPS TO BLOCK", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Selected packages are encrypted locally on this device.", color = TextSecondary, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search installed apps...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { app ->
                val isSelected = app.packageName in selectedPackages
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.appName, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(app.packageName, color = TextSecondary, fontSize = 11.sp)
                    }
                    Checkbox(checked = isSelected, onCheckedChange = { onToggle(app.packageName) })
                }
                HorizontalDivider(color = BorderGrey)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onDone,
            enabled = selectedPackages.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("CONTINUE (${selectedPackages.size} SELECTED)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PinSetupScreen(onPinConfirmed: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("TRUSTED PERSON PIN", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Hand your phone to a trusted person to set a ${PinManager.MIN_PIN_LENGTH}+ digit PIN. Do not memorize it yourself.",
            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.all(Char::isDigit)) pin = it },
            label = { Text("Enter PIN (min ${PinManager.MIN_PIN_LENGTH} digits)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.all(Char::isDigit)) confirmPin = it },
            label = { Text("Confirm PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(error!!, color = StatusRed, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                when {
                    pin.length < PinManager.MIN_PIN_LENGTH -> error = "PIN must be at least ${PinManager.MIN_PIN_LENGTH} digits."
                    pin != confirmPin -> error = "PINs do not match."
                    else -> onPinConfirmed(pin)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("SAVE ENCRYPTED PIN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DashboardScreen(
    state: MainUiState,
    onEnableStrict: () -> Unit,
    onEditApps: () -> Unit,
    onDisableStrict: () -> Unit,
    onOpenPermissions: () -> Unit,
    onResetVault: () -> Unit
) {
    var elapsedText by remember { mutableStateOf("0h 0m") }

    LaunchedEffect(state.isStrictModeActive, state.strictModeStartMillis) {
        while (state.isStrictModeActive && state.strictModeStartMillis > 0L) {
            val diffSec = (System.currentTimeMillis() - state.strictModeStartMillis) / 1000L
            val days = diffSec / 86400
            val hours = (diffSec % 86400) / 3600
            val mins = (diffSec % 3600) / 60
            elapsedText = if (days > 0) "${days}d ${hours}h ${mins}m" else "${hours}h ${mins}m"
            delay(30_000L)
        }
    }

    val (statusLabel, statusColor) = when (state.protectionHealth) {
        ProtectionHealth.ACTIVE -> "ACTIVE (STRICT)" to StatusGreen
        ProtectionHealth.DEGRADED -> "DEGRADED (PAUSED)" to StatusRed
        ProtectionHealth.STORAGE_ERROR -> "STORAGE ERROR" to StatusRed
        ProtectionHealth.OFF -> "OFF" to StatusAmber
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("UNTOUCH", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

        if (state.protectionHealth == ProtectionHealth.STORAGE_ERROR) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                border = BorderStroke(1.dp, StatusRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("PROTECTION UNAVAILABLE", color = StatusRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        state.storageError ?: "Encrypted vault could not be decrypted. Resetting clears the corrupted vault so you can reconfigure.",
                        color = TextPrimary, fontSize = 13.sp
                    )
                    Button(
                        onClick = onResetVault,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = AccentWhite)
                    ) {
                        Text("RESET CORRUPTED VAULT")
                    }
                }
            }
        } else if (state.protectionHealth == ProtectionHealth.DEGRADED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                border = BorderStroke(1.dp, StatusRed),
                modifier = Modifier.fillMaxWidth().clickable { onOpenPermissions() }
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusRed)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Accessibility or Overlay permission is OFF. Blocking is paused — tap to restore.",
                        color = TextPrimary, fontSize = 13.sp
                    )
                }
            }
        }

        StatusCard(title = "PROTECTION STATUS", value = statusLabel, valueColor = statusColor)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(title = "BLOCKED APPS", value = state.blockedPackages.size.toString(), modifier = Modifier.weight(1f))
            StatusCard(
                title = "TIME AVOIDED",
                value = if (state.protectionHealth == ProtectionHealth.ACTIVE) elapsedText else "—",
                modifier = Modifier.weight(1f)
            )
        }

        StatusCard(title = "BLOCKED ATTEMPTS", value = state.blockedAttempts.toString())

        Spacer(modifier = Modifier.weight(1f))

        if (state.protectionHealth != ProtectionHealth.STORAGE_ERROR) {
            if (!state.isStrictModeActive) {
                Button(
                    onClick = onEnableStrict,
                    enabled = state.blockedPackages.isNotEmpty() && state.permissionStatus.coreProtectionReady,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
                ) {
                    Text("ACTIVATE STRICT MODE", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onDisableStrict,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Text("DISABLE STRICT MODE (PIN REQUIRED)", color = TextSecondary)
                }
            }

            OutlinedButton(
                onClick = onEditApps,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Text("MODIFY BLOCKED APPS (PIN REQUIRED)", color = TextPrimary)
            }
        }

        TextButton(onClick = onOpenPermissions, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Permission & Tamper Status", color = TextSecondary)
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderGrey)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = TextSecondary, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StrictModeConfirmScreen(blockedCount: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Shield, contentDescription = null, tint = AccentWhite, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text("ACTIVATE STRICT MODE?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "You are about to lock $blockedCount application(s). Once active:\n\n" +
                "• No temporary breaks or snooze.\n" +
                "• Persists across reboots (after unlock) and app reinstalls.\n" +
                "• Disabling or changing apps requires the trusted person's PIN.",
            color = TextSecondary, fontSize = 14.sp, lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("LOCK NOW", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", color = TextSecondary)
        }
    }
}

@Composable
private fun PermissionsScreen(state: MainUiState, onRefresh: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PERMISSIONS & PROTECTION", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Grant these local permissions so UNTOUCH can enforce blocking reliably on Funtouch OS 15.",
            color = TextSecondary, fontSize = 13.sp
        )
        PermissionRow("Accessibility Service (Required)", state.permissionStatus.accessibilityEnabled) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow("Display Over Other Apps (Required)", state.permissionStatus.overlayGranted) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
        PermissionRow("Usage Access (Alert Monitor)", state.permissionStatus.usageStatsGranted) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermissionRow("Device Admin (Uninstall Friction)", state.permissionStatus.deviceAdminActive) {
            val comp = ComponentName(context, UntouchDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents accidental uninstallation of UNTOUCH.")
            }
            context.startActivity(intent)
        }
        PermissionRow("Ignore Battery Optimization", state.permissionStatus.batteryOptimizationIgnored) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow("Notifications (Tamper Alerts)", state.permissionStatus.notificationsGranted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("GO TO DASHBOARD", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (granted) StatusGreen else BorderGrey)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) StatusGreen else StatusAmber
            )
        }
    }
}

@Composable
private fun AdminAuthScreen(error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("ADMINISTRATOR PIN", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Ask your trusted person to enter the PIN to authorize this change.", color = TextSecondary, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.all(Char::isDigit)) pin = it },
            label = { Text("Enter Administrator PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(error, color = StatusRed, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onSubmit(pin) },
            enabled = pin.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentWhite, contentColor = PureBlack)
        ) {
            Text("VERIFY PIN", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", color = TextSecondary)
        }
    }
}
