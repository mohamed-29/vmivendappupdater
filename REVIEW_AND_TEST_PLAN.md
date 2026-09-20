# Updater review and acceptance tests

All implementation changes are in `vmivendappupdater-main`. The iVend project is a read-only reference. Its JavaScript tests run in `.review-tests/ivend`, an isolated copy; its Python tests run with bytecode writes disabled.

## Implemented recovery behavior

1. Check/download failures, an invalid SHA-256, a wrong package, an unsigned archive or incompatible minimum Android version do not uninstall iVend.
2. A validated iVend APK is installed in place first.
3. A confirmed package-manager installation failure triggers `pm uninstall com.ivendapp`, followed by installation of that same local APK. This deletes private app data. Root denial and ambiguous command timeouts do not trigger uninstall.
4. Failed reinstall is reported as failure, retains the APK, and retains the recovery screen. A later update attempt can reuse a matching verified APK. No successful hash is stored until the package is installed.
5. HOME and foreground checks run from the updater service, with a nominal 10-second delay between completed checks. Slow root commands can increase this interval. Configuration access has a five-minute maintenance window; installation has a six-minute window.
6. Native Java crashes are filtered by iVend's PID. Caught React render errors are read from iVend's own log. Up to ten diagnostic snapshots are retained privately. Upload functionality was removed in 1.0.13.

## Review findings and limitations

| Finding | Result / required follow-up |
| --- | --- |
| Root output was read to EOF before timeout, allowing indefinite hangs | Fixed: concurrent bounded output drain and timed process wait. |
| Service used `dataSync` for continuous supervision | Changed to declared `specialUse`; Android 15 limits `dataSync` duration and boot starts. [Android documentation](https://developer.android.com/develop/background-work/services/fgs/service-types). |
| Boot receiver executed slow root operations on the main thread and accessed credential storage before unlock | Removed locked-boot delivery; service owns supervision, with a bounded asynchronous root start fallback. |
| Update progress closed automatically after 60 seconds | Removed; recovery screen stays visible when reinstall fails. |
| Matching saved hash skipped installation even when app was missing | Fixed: installed-package check required. |
| Native log capture mixed unrelated apps' errors | Fixed: PID-scoped filtering and regression tests. |
| Earlier comments claimed iVend itself must become Device Owner | Corrected: the updater can launch another allowlisted app in lock task mode on Android 9+. [Android documentation](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode). |
| Root-only recovery is not true kiosk containment | Optional Device Owner receiver/policy added. Enrollment is a deployment step; no device was enrolled here. Root-only operation may briefly expose other screens. |
| iVend `SET_TIME` calls Android AlarmManager and declares a system-level permission | Cannot grant this with ordinary `pm grant`. Requires OEM/system provisioning; iVend remains unchanged. |
| iVend stores configuration/catalog in private files and primary sales/promotion data in AsyncStorage | Uninstall removes these. External mirrors exist for some transaction data, but do not assume every pending payment survives. Test on a disposable machine; do not start a clean reinstall during a live payment. |
| iVend catches render errors in ErrorBoundary | It stays in iVend, but may show an error screen; updater now retains those diagnostics. Not every business error or frozen UI is detectable externally. |
| iVend Logger appends an open JSON array with trailing commas | Treat its log as text/individual entries; it is not a complete JSON array. No edit made to iVend. |
| Foreground service uses START_STICKY | Helps recovery from reclamation, but does not defeat force-stop, power loss or all OEM restrictions. |
| Release signing in the supplied project uses the debug signing configuration | Release-mode APK is a test/deployment candidate; production rollout needs the existing updater signing key. A different key cannot update an installed updater in place. |
| Current Flutter minimum Android version is 24 | Updater APK requires Android 7.0 or later. Managed cross-app lock task requires Android 9.0 or later. |

## Automated checks

- Flutter widget tests: status display, corrupt logs, download percentage, read-only package, save arguments, empty URL, platform error handling.
- Native recovery tests: successful update; uninstall/reinstall order and exact target; root denied/non-root; update timeout; unknown shell failure; uninstall failure/timeout; reinstall failure; misleading exit code; shell quoting.
- Diagnostic tests: interleaved crashes, lookalike package, normal logs.
- iVend existing JavaScript suites: vending state, promotions, sales, product/catalog/image cache, bootstrap, sockets, settings, cancellation and UI.
- iVend Python bridge: simulator, credit handling, packet duplicates, command correlation, retries, uncertain operations and corrupted state.
- Android release build, unit tests and lint.

### Executed results

| Check | Result |
| --- | --- |
| Updater Flutter widget tests | 7 passed |
| Native Kotlin recovery and diagnostic tests | 14 passed (also rerun after timeout compatibility fix) |
| iVend JavaScript, exact package-lock dependency versions | 17 suites / 97 tests passed |
| iVend TypeScript `tsc --noEmit`, exact lockfile | Passed |
| iVend Python bridge | 17 passed; bytecode writes disabled |
| Updater Flutter analysis | Passed, no issues |
| Android release compilation | Passed: `assembleRelease`, 2026-09-09 |
| Android release unit tests | 14 passed, zero failures/errors (same native cases counted above) |
| Android release lint | Passed: 0 errors, 29 warnings, 1 hint |
| Physical vending-machine scenarios | Not run: no Android device connected |

Total unique automated test cases passed: 135. Creating a physical test case does not mean that scenario has passed on a machine.

### Verified release APK

- File: `build/app/outputs/flutter-apk/app-release.apk`
- Version: `1.0.2` (code `3`); package: `com.example.vmivendappupdater`.
- Size: 48,736,760 bytes.
- Minimum API 24 (Android 7.0); target API 36.
- Architectures: `armeabi-v7a`, `arm64-v8a`, `x86_64`.
- APK Signature Scheme v2 verification passed. Manifest is not debuggable.
- Signer: Android Debug, matching the repository's existing release signing configuration. This is not evidence of compatibility with the signer on an installed machine.
- SHA-256: `EB970A8D86C2CF1535C89F166C4C0C8C4D6087175C70F0A5BC20CB0C0C15EA01`.
- Recovery-screen back handling migrated to AndroidX `OnBackPressedDispatcher`, fixing the Android 16 predictive-back lint error. Windows local SDK paths escaped correctly.

Remaining lint warnings cover dependency-version updates, Kotlin style suggestions, synchronous preference persistence, resource localization, a redundant legacy drawable folder, modern device-transfer backup rules, and the existing exported update-check receiver. That receiver remains compatible with read-only iVend and accepts update-check requests from other apps; it does not accept a caller-supplied APK or package. Device-to-device transfer behavior should be verified before transferring private diagnostics to another device. See `build/app/reports/lint-results-release.html` for the complete report.

## Physical-machine acceptance cases — not run on this host

Use a spare vending machine without live customer payments. Record Android version, root manager, firmware and updater signer before each run.

| ID | Scenario | Expected result |
| --- | --- | --- |
| K01 | Boot with root granted and iVend installed | Updater notification/service present; iVend resolves HOME and appears. |
| K02 | Change default HOME | Guardian restores iVend on its next successful check. |
| K03 | Open Settings/Recents | Managed lock task prevents escape; root-only mode restores iVend after a polling delay. |
| K04 | Enroll updater as Device Owner on an eligible test device | Both packages allowlisted; iVend starts in `LOCK_TASK_MODE_LOCKED`. |
| K05 | Deny root | No uninstall occurs; verify logged failure and managed-policy behavior if enrolled. |
| K06 | Kill updater process (not force-stop) | Android restarts sticky service; verify firmware behavior. |
| K07 | Force-stop updater | Document that Android may prevent restart until explicit launch; do not claim guaranteed recovery. |
| K08 | Open updater configuration | Five-minute bounded admin window; guardian resumes afterwards. |
| U01 | Valid update | One in-place install, unchanged private data, iVend foreground afterwards. |
| U02 | Signature/version incompatibility with valid APK | Confirmed failed update, one uninstall and fresh install; expect private data reset. |
| U03 | Offline/HTTP error/empty response | Existing iVend remains installed; bounded retry status. |
| U04 | Truncated download/checksum mismatch/wrong package/unsigned APK | No uninstall; visible error/retry. |
| U05 | Low disk space or package-manager rejection | Recovery result accurately reported; inspect retained APK and recovery screen. |
| U06 | Uninstall denied | Fresh install step does not run; no false success. |
| U07 | Reinstall fails after uninstall | Recovery screen remains; no saved success hash; local replacement retained. |
| U08 | Root command hangs during install | Timeout is reported; no racing uninstall. |
| U09 | Missing iVend with matching saved hash | Replacement is not skipped. |
| U10 | Reboot during install/uninstall/reinstall | Verify recovery screen and subsequent scheduled retry; test network unavailable too. |
| U11 | Broadcast repeated rapidly / periodic and manual checks overlap | Only one installer active within the process; no repeated destructive sequence. |
| U12 | Existing unsent cash/promotion records before clean reinstall | Compare surviving external mirrors and server records; account for private-data deletion. |
| L01 | Native Java crash in iVend | iVend restored and target-only diagnostics retained. |
| L02 | Crash an unrelated app | Its private stack trace is not retained. |
| L03 | React render error caught by iVend | In-app error screen remains contained; Crash log captured. |
| L04 | More than ten crash snapshots | Retention bounded to ten. |
| L05 | API not configured | No log transmission or fabricated endpoint; local storage only. |
| P01 | Android 11+ all-files access | Verify storage app-op and iVend reads/writes; validate OEM behavior. |
| P02 | iVend sets clock without system provisioning | Expect permission rejection; provision through OEM if required. |

## Reproduction

- `flutter test` and `flutter analyze` in the updater.
- `android/gradlew.bat -p android testReleaseUnitTest lintRelease` after SDK configuration.
- `tools/test-recovery.ps1` runs native recovery tests using the host's Kotlin compiler.
- `tools/build-release.ps1 -InstallSdk` restores Android packages into `.build-tools` and builds the release APK. Host-specific Java/Flutter paths are declared at the top.
- `node node_modules/jest/bin/jest.js --runInBand` in the isolated iVend copy.
- `python -B -m unittest discover -s C:/Users/LOQ/Desktop/IvendApp-master/src/python_protected/tests -v` for the read-only Python bridge tests.

Optional Device Owner enrollment on an eligible test machine: `adb shell dpm set-device-owner com.example.vmivendappupdater/.KioskAdminReceiver`. Do not factory-reset a deployed machine as part of this procedure. Successful enrollment must be verified with Android's device-policy diagnostics.
