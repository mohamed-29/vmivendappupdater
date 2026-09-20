# Updater 1.0.14 (15): recovery checks and device usage

- A timed-out process/focus check or unreadable focus is unknown. It does not authorize a launch or force-stop. Retry delays increase from 4 to 8 to 16 to 30 seconds, returning to normal after a successful observation.
- Brief foreground appearances do not reset the ten-launch/five-minute recovery counter. Reset requires a continuous minute in the foreground without an observed ANR or missing lock-task requirement. Unknown observations and maintenance interrupt that healthy interval.
- Force-stop before a normal relaunch requires a fresh positive ANR observation. Reaching the ten-failed-launch repair threshold still allows the existing reinstall recovery path.
- Window focus is checked first; an activity dump is requested only if window output has no usable focus. HOME remains checked on every guardian pass; routine root setup is reapplied every five minutes, with a 30-second retry when HOME differs. Diagnostics are captured only while the app is unhealthy/outside the foreground. No diagnostic upload was added.
- Both Live Status and the out-of-service/update screen show a cached resource sample: whole-device CPU busy percentage, peak sampled CPU since updater startup, iVend main-process and updater CPU, CPU I/O wait, used/total/free RAM, and internal storage.
- Sampling runs independently of the guardian/UI every 15 seconds, with a three-second root command timeout. It reads small kernel counters and filesystem capacity; it does not run top, scan folders, save history files, or upload samples.
- CPU percentages use total device capacity across all cores. Process readings require two samples of the same PID/start time. Unavailable counters and first samples display a dash, not zero. iVend main-process CPU excludes separate child processes; total CPU includes all processes. The timestamp exposes stale samples. Storage percentage reports capacity, not disk speed; I/O wait is only a supporting clue.
- iVend 1.18 (19) separately caps captured command-response history at the latest 100 messages. All incoming payment/command events continue through normal processing, and a completion message still completes the command even when history is full. Concurrent upload of all pending orders is unchanged.

These changes address identified failure behavior, not a confirmed diagnosis of the historical seven-day slowdown. Device validation is still required: check startup, leave/reenter iVend outside maintenance, verify five-minute operator maintenance, simulate short crashes and a slow/failed health probe, and compare resource readings during normal operation and lag.

Validation completed: 60 updater JVM tests; 8 Flutter widget tests including a 480×640 layout; 15 focused iVend command-response, sales and vending tests; TypeScript type check; updater release compilation and Android lint. Existing lint warnings remain. Updater APK version 1.0.14/code 15 uses the same signing certificate as 1.0.13/code 14. No device installation or hardware performance measurement was performed.

iVend release compilation also completed successfully. Packaged version: 1.18/code 19, ARMv7 and ARM64, matching the previous iVend signing certificate. Both release APKs and SHA-256 checksum files are in `../release-builds/`:

- `vmivendappupdater-1.0.14-code15-health-metrics.apk`
- `IvendApp-1.18-code19-response-cap100.apk`
