# VM iVend Updater Implementation Documentation

## 1. Purpose

This document describes the work completed on the VM iVend updater application, how the updater operates, how it was deployed to the vending machine, the tests performed, and the remaining operational limitation.

The updater has two distinct identifiers:

- **Target version name:** An editable server-side name used to select the update channel. The current value is `ivend.cloud`.
- **Android target package:** The fixed Android application ID `com.ivendapp`. This is the application the updater validates, installs, launches, and protects as the HOME application.

The target version name is not an Android package name. Changing it changes the generated server check URL, but it does not allow the updater to install an unrelated Android application.

## 2. Vending Machine Connection

The vending machine was reached from the laptop through Wi-Fi ADB using:

```text
192.168.137.19:5555
```

The device runs Android 9, API level 28. Root access is available through `su`, although this device's `su` implementation behaves differently from a standard Android shell.

The updater was built on the laptop and installed with ADB using package replacement, which retained the updater's preferences and configuration.

## 3. Editable Target Version and Automatic URL

The configuration screen was changed so the target version name is always editable. It currently contains:

```text
ivend.cloud
```

The update-check URL is generated automatically as:

```text
https://machine.ivend.cloud/api/v1/updates/check/ivend.cloud/
```

The field represents the version or update channel being tested. It no longer behaves like a locked Android package-name field.

For safety, the Android package expected inside every downloaded APK remains fixed as:

```text
com.ivendapp
```

## 4. Update Download and Validation

The updater performs the following sequence:

1. Contacts the generated check URL.
2. Reads the server-provided APK URL and SHA-256 hash.
3. Downloads the APK into the updater's private files directory.
4. Calculates and verifies the downloaded file's SHA-256 hash.
5. Parses the APK with Android's package manager.
6. Confirms that its package name is `com.ivendapp`.
7. Confirms that signer information exists.
8. Confirms that the APK minimum SDK is compatible with the vending machine.
9. Installs the verified APK through root package-manager commands.
10. Confirms that iVend is still installed, saves the successful hash, and relaunches iVend.

APK validation was updated for Android 9. It now accepts both modern `SigningInfo` data and legacy signature metadata. Validation failures now contain the actual cause instead of only reporting a generic incompatible-APK message.

## 5. Root Shell Compatibility Fix

The original root implementation passed a complete multiword command directly to this machine's `su -c`. This `su` implementation treated the entire text as an executable filename, producing errors such as:

```text
su: failed to exec am start ...: No such file or directory
```

Root commands are now explicitly executed through a shell using arguments equivalent to:

```text
su -c sh -c <command>
```

This permits commands containing arguments, spaces, pipes, and normal Android shell syntax to run correctly.

The root identity check was also updated. This vending-machine `su` may print a diagnostic line before command output, for example:

```text
current_uid 10078
0
```

The installer now recognizes an exact output line containing `0` as root rather than requiring the entire output to equal only `0`.

## 6. Android 9 Activity Command Compatibility

The vending machine's Android 9 activity manager did not accept the long command option:

```text
--activity-new-task
```

The updater now uses the compatible intent flag value:

```text
-f 0x14000000
```

This combines the required new-task and clear-top behavior and works on the target machine.

Commands that exist only on newer Android versions are now version-gated. For example, Android role assignment and `MANAGE_EXTERNAL_STORAGE` app-ops are no longer attempted on Android versions that do not support them.

## 7. Installation Recovery

Before installation, the updater displays its update-progress activity. The verified APK is first installed using:

```text
pm install -r <verified-apk>
```

If Android reports a definite signing or package installation failure, the existing recovery logic can uninstall and reinstall the same already-validated APK. This fallback can remove the iVend application's private data, so it is used only after a confirmed installation failure. Timeouts and uncertain results do not trigger an uninstall.

Before the live update, the installed iVend APK and the server APK were inspected. Both were signed by the same certificate, so normal replacement installation was safe and did not require uninstalling iVend or deleting its data.

The live update completed successfully:

- Previous iVend version: `1.0`, version code `1`
- Installed iVend version: `1.14`, version code `15`
- Final updater state: `App is up to date.`

## 8. Permanent iVend HOME Enforcement

iVend already declares the following Android intent categories in its own existing manifest:

```text
android.intent.category.LAUNCHER
android.intent.category.HOME
android.intent.category.DEFAULT
```

No modification to the iVend source was required for it to qualify as an Android HOME application.

The updater now protects `com.ivendapp/.MainActivity` as follows:

1. A foreground watchdog runs every two seconds.
2. It resolves Android's current default HOME activity.
3. If HOME is not `com.ivendapp`, the condition is treated as a crucial error.
4. A deduplicated `home_app_changed` diagnostic event is queued.
5. Diagnostics remain in the bounded local history.
6. The updater repeatedly assigns iVend as HOME.
7. The updater immediately launches or brings iVend back to the foreground.
8. HOME assignment continues to be checked during APK-update maintenance.
9. Package-added, package-replaced, package-changed, package-restarted, and package-removed broadcasts trigger immediate recovery.
10. If iVend is actually removed, the updater queues a crucial missing-app event and schedules an immediate update attempt.

The machine's stock launcher package was disabled for user 0:

```text
com.android.launcher3
```

This is reversible through ADB, but while disabled it is not a competing normal HOME application. Android's low-priority `FallbackHome` remains part of the operating system for boot/package-transition handling.

## 9. Controlled HOME Recovery Test

HOME was deliberately changed from iVend to Launcher3 for a controlled test.

The updater detected:

```text
Android HOME changed from com.ivendapp to com.android.launcher3/com.android.launcher3.Launcher
```

The following results were verified within six seconds:

- A `home_app_changed` crucial event was added to the diagnostic queue.
- Android's resolved HOME activity returned to `com.ivendapp/.MainActivity`.
- iVend returned to the foreground.
- The activity log recorded that iVend was confirmed as HOME again.

After the test, Launcher3 was disabled and iVend was explicitly reassigned as HOME.

## 10. Update Behavior While iVend Is HOME

Android must stop the iVend process briefly while replacing the APK. No user application can remain executing during its own package replacement.

The updater minimizes this unavoidable interval by:

- Keeping HOME assignment enforcement active during maintenance.
- Showing the updater's controlled progress screen during installation.
- Listening for package replacement immediately.
- Relaunching iVend as soon as package replacement completes.
- Rechecking that iVend is the resolved HOME application.

The successful production test showed that iVend returned to the foreground after updating to version 1.14.

## 11. Device Owner Limitation

The updater includes support for Android Device Owner and lock-task mode. Device Owner would provide stronger operating-system enforcement, including preventing users from leaving the kiosk application.

This vending machine was already provisioned (`device_provisioned=1` and `user_setup_complete=1`). Android rejected an attempt to assign the updater as Device Owner. On standard Android, Device Owner normally must be assigned during initial device provisioning, before normal setup is completed.

Therefore, the current machine uses:

- Root-backed HOME assignment.
- Disabled stock Launcher3.
- A persistent foreground service.
- A two-second recovery watchdog.
- Immediate package-event recovery.

This is strong recovery enforcement, but it is not identical to operating-system Device Owner lock-task mode. For the strongest possible future deployment, provision the updater as Device Owner immediately after a factory reset and before completing Android setup.

## 12. Crucial Diagnostic Classification

The updater treats the following events as crucial:

- Android HOME changed away from iVend.
- iVend missing or removed.
- iVend missing after an update attempt.
- Java, Kotlin, React Native, or native crashes.
- Fatal signals and tombstones.
- Application-not-responding events.
- Initialization, configuration, or bootstrap failures.
- Repeated vending-machine-controller or backend failures.
- Dispensing timeouts.
- Vend or refund failure after payment.
- Uncertain or duplicate transaction state.
- Promotion recovery failures.

Sensitive information such as authorization values, tokens, card details, FCRN data, payment identifiers, and QR-related secrets is redacted before diagnostic data is stored locally.

Queued events use fingerprints to prevent identical entries from filling the queue repeatedly.

## 13. Local diagnostics only

As of updater 1.0.13, all diagnostic uploading is removed. No upload client, endpoint, token build configuration, or upload retry loop is included. The bounded local diagnostic history and live status screen remain available for troubleshooting.

## 14. Build and Verification

The release build is produced with:

```powershell
.\tools\build-release.ps1
```

The build script runs:

- Android release assembly.
- Kotlin/JVM unit tests.
- Android release lint.
- Flutter release asset compilation through Gradle.

The final hardened build completed successfully with all configured unit tests and Android lint checks passing.

The APK output is:

```text
build/app/outputs/flutter-apk/app-release.apk
```

## 15. Deployment Procedure

Connect to the machine:

```powershell
.\.build-tools\android-sdk\platform-tools\adb.exe connect 192.168.137.19:5555
```

Install or replace the updater while retaining its configuration:

```powershell
.\.build-tools\android-sdk\platform-tools\adb.exe -s 192.168.137.19:5555 install -r build\app\outputs\flutter-apk\app-release.apk
```

Confirm the resolved HOME application:

```powershell
.\.build-tools\android-sdk\platform-tools\adb.exe -s 192.168.137.19:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Expected result:

```text
com.ivendapp/.MainActivity
```

Confirm the foreground application:

```powershell
.\.build-tools\android-sdk\platform-tools\adb.exe -s 192.168.137.19:5555 shell dumpsys window windows
```

The `mCurrentFocus` entry should contain `com.ivendapp/com.ivendapp.MainActivity`.

## 16. Main Updater Files Changed

The implementation involved the following updater source areas:

- `lib/main.dart`: Editable target version and generated URL behavior.
- `android/app/build.gradle.kts`: Build-time VMMC endpoint and token configuration.
- `android/app/src/main/AndroidManifest.xml`: Boot, device-admin, service, and managed-package receiver declarations.
- `ManagedTarget.kt`: Separation of editable target version from fixed Android package identity.
- `RootShell.kt`: Compatible rooted shell execution.
- `RootInstaller.kt`: Safe install recovery and vendor `su` root detection.
- `UpdateWorker.kt`: Download, detailed APK validation, installation, and recovery.
- `IvendKioskGuardian.kt`: HOME verification, crucial mismatch logging, relaunch, and kiosk recovery.
- `KioskPolicy.kt`: Optional Device Owner and lock-task configuration.
- `UpdateForegroundService.kt`: Continuous two-second watchdog.
- `ManagedAppReceiver.kt`: Immediate package-event recovery.
- `CriticalDiagnostics.kt`: Crucial-event classification, redaction, and queueing.
- `CrashDiagnostics.kt`: Native-crash and ANR detection.
- Related Kotlin unit tests: Root, recovery, diagnostics, API request, and validation behavior.

## 17. Changes to IvendApp-master

No files were edited in:

```text
C:\Users\LOQ\Desktop\IvendApp-master
```

Its existing Android manifest was read only to verify that `com.ivendapp.MainActivity` already declares the `HOME`, `DEFAULT`, and `LAUNCHER` categories. All implementation changes described in this document were made in:

```text
C:\Users\LOQ\Desktop\vmivendappupdater-main
```

## 18. Current Final State

At the end of testing:

- The updater is installed and its foreground service is running.
- iVend version `1.14` is installed.
- iVend is Android's resolved HOME application.
- iVend is the foreground application.
- Launcher3 is disabled for the primary user.
- HOME mismatch detection and recovery passed a controlled live test.
- Crucial HOME mismatch logging is active.
- Update checking reports that the application is up to date.
- Diagnostic uploading was removed in 1.0.13; historical local entries are not uploaded.
