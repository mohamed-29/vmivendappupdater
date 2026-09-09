package com.example.vmivendappupdater

object CrashDiagnostics {
    fun nativeCrash(raw: String, packageName: String = ManagedTarget.DEFAULT_PACKAGE): String {
        val lines = raw.lines()
        val process = Regex("Process: ${Regex.escape(packageName)}(?:,|$)")
        val pid = Regex("\\(\\s*(\\d+)\\)")
        val targetPids = lines.filter { process.containsMatchIn(it) }
            .mapNotNull { pid.find(it)?.groupValues?.get(1) }.toSet()
        val runtime = lines.filter { line ->
            line.contains("AndroidRuntime") && pid.find(line)?.groupValues?.get(1) in targetPids
        }
        val native = lines.filter { line ->
            line.contains(packageName) &&
                (line.contains("Fatal signal", ignoreCase = true) ||
                    line.contains("tombstone", ignoreCase = true) ||
                    line.contains("backtrace", ignoreCase = true))
        }
        return CriticalDiagnostics.redact((runtime + native).distinct().takeLast(100).joinToString("\n"))
    }

    fun anr(raw: String, packageName: String = ManagedTarget.DEFAULT_PACKAGE): String =
        CriticalDiagnostics.redact(raw.lineSequence().filter { line ->
            line.contains("ANR in $packageName") ||
                (line.contains(packageName) && line.contains("am_anr"))
        }.toList().takeLast(50).joinToString("\n"))
}
