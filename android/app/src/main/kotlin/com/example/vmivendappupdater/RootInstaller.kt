package com.example.vmivendappupdater

/** The caller validates the APK before this state machine can uninstall anything. */
class InstallRecovery(private val shell: (String, Long) -> ShellResult) {
    enum class Outcome { UPDATED, REINSTALLED, ROOT_UNAVAILABLE, UPDATE_UNCERTAIN, UPDATE_REJECTED, UNINSTALL_FAILED, REINSTALL_FAILED }

    fun install(
        apkPath: String,
        log: (String) -> Unit = {},
        targetPackage: String = ManagedTarget.DEFAULT_PACKAGE,
        repair: Boolean = false
    ): Outcome {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(targetPackage)) {
            "Invalid target package"
        }
        val apk = RootShell.quote(apkPath)
        // Some vending-machine su builds print the caller UID before the root
        // command output (for example, "current_uid 10078\n0"). Require a
        // successful command and an exact output line of 0 instead of requiring
        // the entire output to contain only 0.
        if (!shell("id -u", 10).let { result ->
                result.succeeded && result.output.lineSequence().any { it.trim() == "0" }
            }) {
            return Outcome.ROOT_UNAVAILABLE
        }
        val update = shell("pm install -r $apk", 90)
        log("In-place install: ${update.output.take(1500)} (exit=${update.code}, timeout=${update.timedOut})")
        if (success(update)) return Outcome.UPDATED
        // A timeout is ambiguous: never race an uninstall against an active install.
        if (update.timedOut) return Outcome.UPDATE_UNCERTAIN
        val failureCodes = Regex("(?m)^\\s*Failure \\[([A-Z0-9_]+)(?=[:\\]])")
            .findAll(update.output).map { it.groupValues[1] }.toList()
        if (failureCodes.isEmpty()) return Outcome.UPDATE_UNCERTAIN
        // A deliberately selected older release may require a clean install.
        // Every other rejection must preserve the existing app and its data,
        // especially signature mismatch, insufficient space and incompatible ABI.
        if (!repair && failureCodes.any { it != "INSTALL_FAILED_VERSION_DOWNGRADE" }) {
            log("Update rejected (${failureCodes.distinct().joinToString()}); installed app and private data retained.")
            return Outcome.UPDATE_REJECTED
        }
        log("In-place install rejected; uninstalling $targetPackage and reinstalling the verified APK. Private app data will be removed.")
        val uninstall = shell("pm uninstall $targetPackage", 60)
        log("Uninstall: ${uninstall.output.take(1500)}")
        if (!success(uninstall)) return Outcome.UNINSTALL_FAILED
        log("iVend uninstalled; reinstalling the verified local APK.")
        val reinstall = shell("pm install " + apk, 90)
        log("Clean install: ${reinstall.output.take(1500)}")
        return if (success(reinstall)) Outcome.REINSTALLED else Outcome.REINSTALL_FAILED
    }

    private fun success(result: ShellResult) = result.succeeded &&
        result.output.lineSequence().any { it.trim() == "Success" }
}

object RootInstaller {
    fun install(apkPath: String, targetPackage: String, log: (String) -> Unit, repair: Boolean = false): InstallRecovery.Outcome =
        InstallRecovery(RootShell::run).install(apkPath, log, targetPackage, repair)

    /** Replace the updater without uninstalling it, preserving Device Owner and app data. */
    fun installInPlace(apkPath: String, log: (String) -> Unit): InstallRecovery.Outcome =
        InPlaceInstaller(RootShell::run).install(apkPath, log)
}

class InPlaceInstaller(private val shell: (String, Long) -> ShellResult) {
    fun install(apkPath: String, log: (String) -> Unit = {}): InstallRecovery.Outcome {
        val root = shell("id -u", 10)
        if (!root.succeeded || root.output.lineSequence().none { it.trim() == "0" }) {
            return InstallRecovery.Outcome.ROOT_UNAVAILABLE
        }
        val result = shell("pm install -r -d ${RootShell.quote(apkPath)}", 90)
        if (result.succeeded && result.output.lineSequence().any { it.trim() == "Success" }) {
            return InstallRecovery.Outcome.UPDATED
        }
        log("Updater replacement was rejected; the installed updater and Device Owner state were retained.")
        return if (result.timedOut) InstallRecovery.Outcome.UPDATE_UNCERTAIN
        else InstallRecovery.Outcome.UPDATE_REJECTED
    }
}
