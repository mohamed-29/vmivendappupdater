package com.example.vmivendappupdater

import org.junit.Assert.*
import org.junit.Test

class InstallRecoveryTest {
    @Test fun explicitCrashRecoveryCleansAfterConfirmedReinstallFailure() {
        val commands = mutableListOf<String>()
        val results = listOf(ShellResult(0, "0"),
            ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
            ShellResult(0, "Success"), ShellResult(0, "Success"))
        var index = 0
        val outcome = InstallRecovery { command, _ -> commands.add(command); results[index++] }
            .install("/verified.apk", repair = true)
        assertEquals(InstallRecovery.Outcome.REINSTALLED, outcome)
        assertEquals("pm uninstall com.ivendapp", commands[2])
    }

    @Test fun crashRecoveryTimeoutNeverRacesUninstall() {
        val commands = mutableListOf<String>()
        val outcome = InstallRecovery { command, _ ->
            commands.add(command)
            if (command == "id -u") ShellResult(0, "0")
            else ShellResult(-1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", true)
        }.install("/verified.apk", repair = true)
        assertEquals(InstallRecovery.Outcome.UPDATE_UNCERTAIN, outcome)
        assertEquals(2, commands.size)
    }
    private val ok = ShellResult(0, "Success")
    private val root = ShellResult(0, "0")
    private val failure = ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")
    private val downgrade = ShellResult(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")
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

    @Test fun confirmedDowngradeUninstallsThenInstallsSameVerifiedApk() {
        assertEquals(InstallRecovery.Outcome.REINSTALLED, run(root, downgrade, ok, ok))
        assertEquals("pm uninstall com.ivendapp", calls[2])
        assertEquals(calls[1].replace("pm install -r ", "pm install "), calls[3])
    }

    @Test fun configuredPackageIsUsedForRecoveryUninstall() {
        var index = 0
        val results = listOf(root, downgrade, ok, ok)
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

    @Test fun signatureMismatchNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATE_REJECTED, run(root, failure))
        assertEquals(2, calls.size)
    }

    @Test fun storageAbiAndOtherRejectionsPreserveInstalledApp() {
        listOf("INSTALL_FAILED_INSUFFICIENT_STORAGE", "INSTALL_FAILED_NO_MATCHING_ABIS",
            "INSTALL_FAILED_INVALID_APK", "INSTALL_FAILED_VERSION_DOWNGRADE_EXTRA").forEach { code ->
            calls.clear()
            assertEquals(code, InstallRecovery.Outcome.UPDATE_REJECTED,
                run(root, ShellResult(1, "Failure [$code: package rejected]")))
            assertEquals(code, 2, calls.size)
        }
    }

    @Test fun downgradeWithPackageManagerDetailAllowsReinstall() {
        assertEquals(InstallRecovery.Outcome.REINSTALLED,
            run(root, ShellResult(1, "current_uid 10078\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 2 is older than current 3]"), ok, ok))
        assertEquals(4, calls.size)
    }

    @Test fun mentioningDowngradeInsideAnotherFailureNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATE_REJECTED,
            run(root, ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: INSTALL_FAILED_VERSION_DOWNGRADE]")))
        assertEquals(2, calls.size)
    }

    @Test fun conflictingFailureCodesNeverUninstall() {
        assertEquals(InstallRecovery.Outcome.UPDATE_REJECTED,
            run(root, ShellResult(1, downgrade.output + "\n" + failure.output)))
        assertEquals(2, calls.size)
    }

    @Test fun timeoutWithDowngradeOutputNeverUninstalls() {
        assertEquals(InstallRecovery.Outcome.UPDATE_UNCERTAIN,
            run(root, ShellResult(-1, downgrade.output, true)))
        assertEquals(2, calls.size)
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
        assertEquals(InstallRecovery.Outcome.UNINSTALL_FAILED, run(root, downgrade, ShellResult(1, "Failure")))
        assertEquals(3, calls.size)
    }

    @Test fun uninstallTimeoutDoesNotRaceReinstall() {
        assertEquals(InstallRecovery.Outcome.UNINSTALL_FAILED, run(root, downgrade, ShellResult(-1, "", true)))
        assertEquals(3, calls.size)
    }

    @Test fun reinstallFailureIsNotReportedAsSuccess() {
        assertEquals(InstallRecovery.Outcome.REINSTALL_FAILED, run(root, downgrade, ok, failure))
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

    @Test fun updaterReplacementAllowsInPlaceDowngradeWithoutUninstall() {
        val commands = mutableListOf<String>()
        val results = listOf(root, ok)
        var index = 0
        val outcome = InPlaceInstaller { command, _ ->
            commands += command
            results[index++]
        }.install("/data/user/0/updater/files/updater-update.apk")

        assertEquals(InstallRecovery.Outcome.UPDATED, outcome)
        assertEquals(2, commands.size)
        assertTrue(commands[1].startsWith("pm install -r -d "))
        assertFalse(commands.any { it.startsWith("pm uninstall ") })
    }

    @Test fun rejectedUpdaterReplacementNeverUninstallsDeviceOwner() {
        val commands = mutableListOf<String>()
        val results = listOf(root, downgrade)
        var index = 0
        val outcome = InPlaceInstaller { command, _ ->
            commands += command
            results[index++]
        }.install("/data/user/0/updater/files/updater-update.apk")

        assertEquals(InstallRecovery.Outcome.UPDATE_REJECTED, outcome)
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.startsWith("pm uninstall ") })
    }
}
