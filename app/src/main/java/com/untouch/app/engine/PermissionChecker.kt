package com.untouch.app.engine

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.untouch.app.receivers.UntouchDeviceAdminReceiver

data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val usageStatsGranted: Boolean,
    val overlayGranted: Boolean,
    val deviceAdminActive: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val notificationsGranted: Boolean
) {
    val coreProtectionReady: Boolean
        get() = accessibilityEnabled && overlayGranted
}

object PermissionChecker {

    fun checkAll(context: Context): PermissionStatus {
        return PermissionStatus(
            accessibilityEnabled = isAccessibilityEnabled(context),
            usageStatsGranted = isUsageStatsGranted(context),
            overlayGranted = Settings.canDrawOverlays(context),
            deviceAdminActive = isDeviceAdminActive(context),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
            notificationsGranted = isNotificationsGranted(context)
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val targetId = "${context.packageName}/${BlockingAccessibilityService::class.java.name}"
        val shortTargetId = "${context.packageName}/.engine.BlockingAccessibilityService"
        return enabledServices.any {
            it.id.equals(targetId, ignoreCase = true) || it.id.equals(shortTargetId, ignoreCase = true)
        }
    }

    fun isUsageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, UntouchDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(component)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
