# Keep Android components referenced in AndroidManifest.xml
-keep public class com.untouch.app.ui.MainActivity
-keep public class com.untouch.app.ui.BlockedActivity
-keep public class com.untouch.app.engine.BlockingAccessibilityService
-keep public class com.untouch.app.engine.ProtectionForegroundService
-keep public class com.untouch.app.receivers.BootReceiver
-keep public class com.untouch.app.receivers.PackageEventReceiver
-keep public class com.untouch.app.receivers.UntouchDeviceAdminReceiver

# Preserve Kotlin coroutines & Compose runtime metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
