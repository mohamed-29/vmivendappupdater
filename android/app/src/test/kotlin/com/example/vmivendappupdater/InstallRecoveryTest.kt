package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class InstallRecoveryTest {
    private val ok = ShellResult(0, "Success")
    private val root = ShellResult(0, "0")
    private val failure = ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")
    private val calls = mutableListOf<String>()

    private fun run(vararg results: ShellResult): InstallRecovery.Outcome {
        var index = 0
        return InstallRecovery { command, _ ->
            calls.add(command)
            check(index < results.size) { "Unexpected destructive command: " + command }
            results[index++]
        }.install("/data/user/0/updater/files/update.apk")
    }

    @Test fun successfulUpdateNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATED, run(root, ok))
        assertEquals(2, calls.size)
    }

    @Test fun confirmedFailureUninstallsThenInstallsSameVerifiedApk() {
        assertEquals(InstallRecovery.Outcome.REINSTALLED, run(root, failure, ok, ok))
        assertEquals("pm uninstall com.ivendapp", calls[2])
        assertEquals(calls[1].replace("pm install -r ", "pm install "), calls[3])
    }

    @Test fun configuredPackageIsUsedForRecoveryUninstall() {
        var index = 0
        val results = listOf(root, failure, ok, ok)
        val outcome = InstallRecovery { command, _ ->
            calls.add(command)
            results[index++]
        }.install("/data/user/0/updater/files/update.apk", targetPackage = "ivend.cloud")
        assertEquals(InstallRecovery.Outcome.REINSTALLED, outcome)
        assertEquals("pm uninstall ivend.cloud", calls[2])
    }

    @Test fun rootDeniedNeverInstallsOrUninstalls() {
        assertEquals(InstallRecovery.Outcome.ROOT_UNAVAILABLE, run(ShellResult(1, "denied")))
        assertEquals(listOf("id -u"), calls)
    }

    @Test fun nonRootIdentityNeverInstalls() {
        assertEquals(InstallRecovery.Outcome.ROOT_UNAVAILABLE, run(ShellResult(0, "2000")))
        assertEquals(1, calls.size)
    }

    @Test fun rootIdentityAllowsVendorSuDiagnosticPrefix() {
        assertEquals(
            InstallRecovery.Outcome.UPDATED,
            run(ShellResult(0, "current_uid 10078\n0"), ok)
        )
        assertEquals(2, calls.size)
    }

    @Test fun installTimeoutNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATE_UNCERTAIN, run(root, ShellResult(-1, "", true)))
        assertEquals(2, calls.size)
    }

    @Test fun unknownShellFailureNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATE_UNCERTAIN, run(root, ShellResult(1, "su: inaccessible")))
        assertEquals(2, calls.size)
    }

    @Test fun uninstallFailureStopsRecovery() {
        assertEquals(InstallRecovery.Outcome.UNINSTALL_FAILED, run(root, failure, ShellResult(1, "Failure")))
        assertEquals(3, calls.size)
    }

    @Test fun uninstallTimeoutDoesNotRaceReinstall() {
        assertEquals(InstallRecovery.Outcome.UNINSTALL_FAILED, run(root, failure, ShellResult(-1, "", true)))
        assertEquals(3, calls.size)
    }

    @Test fun reinstallFailureIsNotReportedAsSuccess() {
        assertEquals(InstallRecovery.Outcome.REINSTALL_FAILED, run(root, failure, ok, failure))
        assertEquals(4, calls.size)
    }

    @Test fun zeroExitWithoutPackageManagerSuccessIsNotSuccess() {
        assertEquals(InstallRecovery.Outcome.UPDATE_UNCERTAIN, run(root, ShellResult(0, "")))
    }

    @Test fun shellArgumentsAreQuoted() {
        assertEquals("'path with spaces'", RootShell.quote("path with spaces"))
        assertEquals("'a'\\''b'", RootShell.quote("a'b"))
    }

    @Test fun rootCommandsUseAnExplicitShellForMultiwordCommands() {
        assertEquals(
            listOf("su", "-c", "sh", "-c", "am start -n com.ivendapp/.MainActivity"),
            RootShell.processArguments("am start -n com.ivendapp/.MainActivity")
        )
    }
}
