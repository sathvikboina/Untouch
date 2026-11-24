package com.untouch.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import com.untouch.app.engine.ProtectionForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val storage = SecureStorage.get(context)
            when (val res = storage.readConfig()) {
                is StorageResult.Success -> {
                    if (res.value.isStrictModeActive) {
                        ProtectionForegroundService.safeStart(context)
                    }
                }
                is StorageResult.Error -> {
                    ProtectionForegroundService.postAlertNotification(
                        context,
                        "Storage Error After Reboot",
                        "UNTOUCH could not read its encrypted vault. Tap to inspect."
                    )
                }
            }
        }
    }
}
