package com.untouch.app.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class UntouchDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Device Admin weakens UNTOUCH uninstall friction. Only proceed with your trusted person."
    }
}
