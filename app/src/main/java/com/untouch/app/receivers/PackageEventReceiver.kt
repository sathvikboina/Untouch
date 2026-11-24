package com.untouch.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import com.untouch.app.engine.ProtectionForegroundService

class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val installedPackage = intent.data?.schemeSpecificPart ?: return
        val storage = SecureStorage.get(context)
        val config = when (val res = storage.readConfig()) {
            is StorageResult.Success -> res.value
            is StorageResult.Error -> return
        }

        if (config.isStrictModeActive && installedPackage in config.blockedPackages) {
            ProtectionForegroundService.postAlertNotification(
                context,
                "Blocked App Reinstalled",
                "Strict Mode remains active — this application is still locked."
            )
        }
    }
}
