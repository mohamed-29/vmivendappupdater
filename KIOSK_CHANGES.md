# Kiosk changes — 11 September 2026

Updater release 1.0.4 (code 5) adds visible recovery and self-update status to the earlier kiosk changes. VMMC and iVend source are unchanged in this release.

## 1. Ten-minute Android Settings window

Both existing authorized entry routes (TOTP and the keyed VMC event) still lead to iVend Settings. Its Android Settings button now asks the updater for a ten-minute window before launching Android Settings. Returning to iVend closes the window early. Expiry is measured with elapsed time, so changing the clock does not extend it, and a reboot invalidates the window.

When the updater is Device Owner, Settings is temporarily added to its lock-task allowlist. Opening the updater launcher itself no longer grants maintenance. The two apps identify each other by Android caller UID/package through small ContentProvider calls; no backend or credential redesign was added.

## 2. Recovery when iVend fails

The updater's native recovery screen is the persistent HOME handler, and it normally starts iVend. This intentional HOME change means the machine still has a HOME screen when iVend is uninstalled during a downgrade. The guardian restores this HOME assignment if it changes.

Missing/unlaunchable iVend shows Out of service. The screen now shows a live status box with the current operation, update channel, self-update status, status age and recent timestamped events. Back is blocked, and managed devices use lock task. iVend also joins managed lock task on resume, including Android 7/8. Diagnostics are retained locally; uploading was removed in 1.0.13.

The guardian allows ten launch attempts in a rolling five-minute window, with at least ten seconds between attempts. On the next failed check it force-stops iVend, holds the recovery screen, and requests repair. Root process inspection detects Android's not-responding flag and checks actual window focus, without a synchronous call into the frozen application. A missing package or missing launcher requests repair immediately. HOME/permission repair commands have bounded execution times.

The last verified iVend APK is retained as `last-ivend.apk` with its checksum. Recovery tries that file first, including offline; if unavailable or corrupt it fetches the published APK. Earlier updater releases deleted their APK after success, so machines upgrading from them need a download before a repair cache exists. A separate recovery work queue prevents an offline normal update from blocking local repair. Retry limits/cooldowns remain, with the cover held after unsuccessful repair. This detects Android ANRs and lost focus; it is not a watchdog for every possible application-level freeze.

The default backend release channel is `ivend.cloud`. Existing legacy default settings migrate once; custom channel names and custom check URLs are preserved. The target channel remains editable during authorized operator maintenance, and the automatic check URL follows it. The installed Android package remains `com.ivendapp`.

## 3. Updates and downgrades

The updater downloads first, then asks iVend whether it is idle. iVend rejects installation during an active sale, known/unknown cash balance, payment, motor/test operation, pending command, initialization, or operator Settings. A positive response blocks new app-originated sales and shows an updating screen. The updater waits for its native cover to be visible before running the installer. Completion/failure releases the app gate; a ten-minute fallback releases it if the updater disappears.

Normal updates use in-place installation. An exact Android `INSTALL_FAILED_VERSION_DOWNGRADE` rejection still triggers uninstall/reinstall, preserving the requested downgrade behavior. Recovery reinstalls the cached APK first; a confirmed Android package-manager rejection allows uninstall followed by a clean install, as requested. A timeout or ambiguous installer output does not trigger uninstall or a competing install. **A clean reinstall/downgrade deletes iVend private data**, including any offline records stored there. Package/checksum/minimum-SDK validation still runs before installation.

No response from an installed app defers normal updates. Crash recovery deliberately bypasses that approval, since the app may be broken; it does not prove a payment has settled. Provider approval/release calls have a bounded wait so an ANR cannot indefinitely block the worker. Older iVend APKs without coordination defer normal updates. Downgrading to an older app without coordination removes its ability to request the Settings window or approve subsequent normal updates.

The updater also checks the VMMC release channel `appupdater.ivend.cloud`. Its APK keeps the Android package `com.example.vmivendappupdater` and requires the same signing certificate as the installed updater. Device Owner machines submit an Android PackageInstaller session; root-only machines start an independent root shell so replacing the updater does not kill its own installer. Replacement is in-place only: the updater never uninstalls itself. iVend's existing check broadcast triggers both checks, and the updater also schedules periodic checks.

Self-update reports checking, download progress, verification, idle/maintenance deferral, installation and errors in both the native recovery box and Flutter status screen. The replacement is confirmed using the actually installed APK hash. Android rejection is reported immediately when received; no confirmed replacement after three minutes reports that result and releases the app gate. Stale install callbacks are ignored. A successful self-update still requires a compatible APK, matching signer and functioning Device Owner or root installation privileges; these ROM-specific behaviors need a machine test.

## Verification and machine checks

- App TypeScript check passed.
- App Jest: 19 suites, 117 tests passed, including update admission and Settings controls.
- App Android Java compilation passed (`compilePy39DebugJavaWithJavac`), including the required Metro/Hermes bundle step. A host-only Gradle init script limited Metro to two workers; production build configuration was unchanged.
- Updater release verification and APK checksum are recorded in `../release-builds/RELEASES-20260911.md` after the final build.
- No physical-machine acceptance case or deployment was performed in this change.

Before deploying the pair, use a spare machine to check:

1. TOTP and keyed-VMC Settings entry; Android Settings remains usable for ten minutes, return closes it, and reboot/clock changes do not extend it.
2. Home/Back/Recents and the shade; ten repeated iVend crashes/ANRs within five minutes, missing package, and changed HOME. Confirm the status box updates throughout repair.
3. Offline repair using the retained APK; confirmed repair-install rejection followed by clean reinstall; installer timeout without uninstall; failed clean install remains on recovery.
4. Update requested during cash/card payment, dispensing, and idle; intentional downgrade; signature/storage rejection.
5. Self-update on Device Owner and root-only machines: download progress, successful process replacement, wrong signer, installer rejection and three-minute unconfirmed-result reporting.

Strict Android containment requires working Device Owner/lock-task provisioning. Root-only operation restores the kiosk after polling and cannot promise zero Android exposure. No machine was enrolled, reset, or deployed from this task.

The source has no verified cash-acceptor inhibit operation. The software gate blocks new app commands and checks the observed balance; it does not physically close a bill acceptor. Confirm the machine's cash behavior while the updating cover is showing before enabling unattended updates.
