package com.untouch.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import com.untouch.app.engine.PermissionChecker
import com.untouch.app.engine.PermissionStatus
import com.untouch.app.engine.ProtectionForegroundService
import com.untouch.app.security.PinManager
import com.untouch.app.security.PinVerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen {
    ONBOARDING,
    APP_SELECTION,
    PIN_SETUP,
    DASHBOARD,
    STRICT_CONFIRM,
    PERMISSIONS,
    ADMIN_AUTH
}

enum class AdminAction {
    EDIT_BLOCKED_APPS,
    DISABLE_STRICT_MODE,
    OPEN_PERMISSIONS
}

enum class ProtectionHealth {
    ACTIVE,
    DEGRADED,
    STORAGE_ERROR,
    OFF
}

data class InstalledApp(
    val appName: String,
    val packageName: String
)

data class MainUiState(
    val currentScreen: Screen = Screen.ONBOARDING,
    val installedApps: List<InstalledApp> = emptyList(),
    val blockedPackages: Set<String> = emptySet(),
    val isStrictModeActive: Boolean = false,
    val strictModeStartMillis: Long = 0L,
    val blockedAttempts: Int = 0,
    val isOnboardingComplete: Boolean = false,
    val permissionStatus: PermissionStatus = PermissionStatus(false, false, false, false, false, false),
    val protectionHealth: ProtectionHealth = ProtectionHealth.OFF,
    val storageError: String? = null,
    val pendingAdminAction: AdminAction? = null,
    val authError: String? = null,
    val lockoutSeconds: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SecureStorage(application)
    private val pinManager = PinManager(storage)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        reloadAllState(initialLoad = true)
        loadInstalledApps()
    }

    fun reloadAllState(initialLoad: Boolean = false) {
        val perms = PermissionChecker.checkAll(getApplication())
        val configRes = storage.readConfig()
        val pinRes = pinManager.isPinConfigured()

        if (configRes is StorageResult.Error) {
            _uiState.update {
                it.copy(
                    currentScreen = Screen.DASHBOARD,
                    permissionStatus = perms,
                    protectionHealth = ProtectionHealth.STORAGE_ERROR,
                    storageError = configRes.reason
                )
            }
            return
        }
        if (pinRes is StorageResult.Error) {
            _uiState.update {
                it.copy(
                    currentScreen = Screen.DASHBOARD,
                    permissionStatus = perms,
                    protectionHealth = ProtectionHealth.STORAGE_ERROR,
                    storageError = pinRes.reason
                )
            }
            return
        }

        val config = (configRes as StorageResult.Success).value
        val pinConfigured = (pinRes as StorageResult.Success).value
        val lockout = (pinManager.getRemainingLockoutSeconds() as? StorageResult.Success)?.value ?: 0L

        val health = when {
            !config.isStrictModeActive -> ProtectionHealth.OFF
            perms.coreProtectionReady -> ProtectionHealth.ACTIVE
            else -> ProtectionHealth.DEGRADED
        }

        val targetScreen = if (initialLoad) {
            if (config.isOnboardingComplete && pinConfigured) Screen.DASHBOARD else Screen.ONBOARDING
        } else {
            _uiState.value.currentScreen
        }

        _uiState.update {
            it.copy(
                currentScreen = targetScreen,
                blockedPackages = config.blockedPackages,
                isStrictModeActive = config.isStrictModeActive,
                strictModeStartMillis = config.strictModeStartTimeMillis,
                blockedAttempts = config.blockedAttemptCount,
                isOnboardingComplete = config.isOnboardingComplete,
                permissionStatus = perms,
                protectionHealth = health,
                storageError = null,
                lockoutSeconds = lockout
            )
        }
    }

    fun resetCorruptedVault() {
        ProtectionForegroundService.stop(getApplication())
        storage.resetCorruptedVault()
        reloadAllState(initialLoad = true)
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val myPkg = getApplication<Application>().packageName
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcherIntent, 0)

            val apps = resolved
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { info ->
                    info.packageName != myPkg &&
                        info.packageName != "com.android.settings" &&
                        (info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                }
                .map { info ->
                    InstalledApp(
                        appName = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName
                    )
                }
                .sortedBy { it.appName.lowercase() }

            _uiState.update { it.copy(installedApps = apps) }
        }
    }

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen, authError = null) }
    }

    fun togglePackageSelection(packageName: String) {
        val updated = _uiState.value.blockedPackages.toMutableSet()
        if (!updated.add(packageName)) {
            updated.remove(packageName)
        }
        when (val res = storage.setBlockedPackages(updated)) {
            is StorageResult.Error -> _uiState.update {
                it.copy(protectionHealth = ProtectionHealth.STORAGE_ERROR, storageError = res.reason)
            }
            is StorageResult.Success -> _uiState.update { it.copy(blockedPackages = updated) }
        }
    }

    fun savePinAndFinishOnboarding(pin: String) {
        when (val r1 = pinManager.setPin(pin)) {
            is StorageResult.Error -> {
                _uiState.update { it.copy(protectionHealth = ProtectionHealth.STORAGE_ERROR, storageError = r1.reason) }
                return
            }
            is StorageResult.Success -> Unit
        }
        storage.setOnboardingComplete(true)
        reloadAllState()
        navigateTo(Screen.PERMISSIONS)
    }

    fun activateStrictMode() {
        when (val res = storage.setStrictModeActive(true)) {
            is StorageResult.Error -> {
                _uiState.update { it.copy(protectionHealth = ProtectionHealth.STORAGE_ERROR, storageError = res.reason) }
            }
            is StorageResult.Success -> {
                ProtectionForegroundService.safeStart(getApplication())
                reloadAllState()
                navigateTo(Screen.DASHBOARD)
            }
        }
    }

    fun requestAdminAction(action: AdminAction) {
        val lockout = (pinManager.getRemainingLockoutSeconds() as? StorageResult.Success)?.value ?: 0L
        _uiState.update {
            it.copy(
                pendingAdminAction = action,
                authError = null,
                lockoutSeconds = lockout,
                currentScreen = Screen.ADMIN_AUTH
            )
        }
    }

    fun submitAdminPin(pin: String) {
        when (val result = pinManager.verifyPin(pin)) {
            is PinVerificationResult.Success -> {
                val action = _uiState.value.pendingAdminAction
                when (action) {
                    AdminAction.EDIT_BLOCKED_APPS -> {
                        _uiState.update {
                            it.copy(currentScreen = Screen.APP_SELECTION, pendingAdminAction = null, authError = null)
                        }
                    }
                    AdminAction.DISABLE_STRICT_MODE -> {
                        storage.setStrictModeActive(false)
                        ProtectionForegroundService.stop(getApplication())
                        reloadAllState()
                        _uiState.update {
                            it.copy(currentScreen = Screen.DASHBOARD, pendingAdminAction = null, authError = null)
                        }
                    }
                    AdminAction.OPEN_PERMISSIONS -> {
                        _uiState.update {
                            it.copy(currentScreen = Screen.PERMISSIONS, pendingAdminAction = null, authError = null)
                        }
                    }
                    null -> navigateTo(Screen.DASHBOARD)
                }
            }
            is PinVerificationResult.Invalid -> {
                _uiState.update {
                    it.copy(authError = "Incorrect PIN. ${result.remainingBeforeLockout} attempt(s) before lockout.")
                }
            }
            is PinVerificationResult.LockedOut -> {
                _uiState.update {
                    it.copy(
                        lockoutSeconds = result.remainingSeconds,
                        authError = "Too many failed attempts. Locked for ${result.remainingSeconds}s."
                    )
                }
            }
            is PinVerificationResult.StorageFailure -> {
                _uiState.update {
                    it.copy(
                        protectionHealth = ProtectionHealth.STORAGE_ERROR,
                        storageError = result.reason,
                        currentScreen = Screen.DASHBOARD
                    )
                }
            }
        }
    }
}
