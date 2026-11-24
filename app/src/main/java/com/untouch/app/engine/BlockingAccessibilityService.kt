package com.untouch.app.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import com.untouch.app.ui.BlockedActivity

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var storage: SecureStorage
    private var lastBlockTimestamp = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = SecureStorage.get(this)
        val configResult = storage.readConfig()
        if (configResult is StorageResult.Success && configResult.value.isStrictModeActive) {
            ProtectionForegroundService.safeStart(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || isProtectedSystemPackage(pkg)) return

        val config = when (val res = storage.readConfig()) {
            is StorageResult.Error -> return
            is StorageResult.Success -> res.value
        }
        if (!config.isStrictModeActive || config.blockedPackages.isEmpty()) return

        if (pkg in config.blockedPackages) {
            enforceBlock(pkg)
        }
    }

    private fun enforceBlock(blockedPackage: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTimestamp < 400L) return
        lastBlockTimestamp = now

        storage.incrementBlockedAttempts()
        performGlobalAction(GLOBAL_ACTION_HOME)

        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(BlockedActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        }
        startActivity(intent)
    }

    private fun isProtectedSystemPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg == "com.android.phone" ||
            pkg == "com.android.dialer" ||
            pkg == "com.google.android.dialer" ||
            pkg == "com.android.server.telecom" ||
            pkg == "com.android.emergency"
    }

    override fun onInterrupt() {
        // Service interrupted by system
    }
}
