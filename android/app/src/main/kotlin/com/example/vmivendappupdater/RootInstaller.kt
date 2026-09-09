package com.example.vmivendappupdater

/** The caller validates the APK before this state machine can uninstall anything. */
class InstallRecovery(private val shell: (String, Long) -> ShellResult) {
    enum class Outcome { UPDATED, REINSTALLED, ROOT_UNAVAILABLE, UPDATE_UNCERTAIN, UNINSTALL_FAILED, REINSTALL_FAILED }

    fun install(
        apkPath: String,
        log: (String) -> Unit = {},
        targetPackage: String = ManagedTarget.DEFAULT_PACKAGE
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
        if (success(update)) return Outcome.UPDATED
        // A timeout is ambiguous: never race an uninstall against an active install.
        if (update.timedOut || !update.output.contains("Failure [")) return Outcome.UPDATE_UNCERTAIN
        log("Installation failed; uninstalling $targetPackage. Private app data will be removed.")
        val uninstall = shell("pm uninstall $targetPackage", 60)
        if (!success(uninstall)) return Outcome.UNINSTALL_FAILED
        log("iVend uninstalled; reinstalling the verified local APK.")
        val reinstall = shell("pm install " + apk, 90)
        return if (success(reinstall)) Outcome.REINSTALLED else Outcome.REINSTALL_FAILED
    }

    private fun success(result: ShellResult) = result.succeeded &&
        result.output.lineSequence().any { it.trim() == "Success" }
}

object RootInstaller {
    fun install(apkPath: String, targetPackage: String, log: (String) -> Unit): InstallRecovery.Outcome =
        InstallRecovery(RootShell::run).install(apkPath, log, targetPackage)
}
