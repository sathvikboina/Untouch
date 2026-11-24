# Untouch

An offline, PIN-governed app blocker for Android. A trusted person sets the
PIN; selected apps are blocked while Strict Mode is active, with no snooze or
temporary unlock.

- 100% local: no internet permission, no analytics, no servers.
- Config and PIN hash are encrypted with an Android Keystore AES-256-GCM key;
  the PIN is stored as a PBKDF2-HMAC-SHA256 hash with a random salt.
- Blocking is enforced by an Accessibility Service, with a foreground health
  monitor that raises tamper alerts when protection is degraded.

## Build

The included GitHub Actions workflow (`.github/workflows/build.yml`) builds a
debug APK on every push and uploads it as the `UNTOUCH-APK` artifact.

To build locally, install JDK 17 and the Android SDK (platform 35), then:

```sh
gradle assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds are signed with the committed `untouch-debug.keystore` so that
successive builds update in place rather than requiring an uninstall (which
would wipe the encrypted PIN and blocked-app list).

## Honest limits

On a stock, non-rooted phone without Device Owner provisioning, an app cannot
prevent its own uninstallation, Safe Mode, or a factory reset. Device Admin
adds uninstall friction and the PIN prevents impulsive in-app changes; neither
stops a determined device owner. Emergency calling and system dialer packages
are permanently excluded from blocking.
