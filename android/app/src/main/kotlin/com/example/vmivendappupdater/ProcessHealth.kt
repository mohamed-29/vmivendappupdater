package com.example.vmivendappupdater

internal object ProcessHealth {
    private fun mentionsTarget(line: String, target: String): Boolean =
        Regex("(?<![A-Za-z0-9_.])${Regex.escape(target)}(?:[/\\s}:]|$)").containsMatchIn(line)

    fun isRunning(dump: String, target: String): Boolean = dump.lineSequence().any { line ->
        line.contains("ProcessRecord{") && mentionsTarget(line, target)
    }

    fun isNotResponding(dump: String, target: String): Boolean {
        var targetProcess = false
        for (line in dump.lineSequence()) {
            if (line.contains("ProcessRecord{")) {
                targetProcess = mentionsTarget(line, target)
            }
            if (targetProcess && line.contains("notResponding=true")) return true
        }
        return false
    }

    /** Handles the focus fields used by old and new Android dumpsys versions. */
    fun isForeground(dump: String, target: String): Boolean = foreground(dump, target) == true

    /** Missing/null focus and unsupported/error output are unknown, not an escape. */
    fun foreground(dump: String, target: String): Boolean? {
        val lines = dump.lineSequence().toList()
        val currentFocus = lines.filter { it.contains("mCurrentFocus") }
            .filter { it.contains("Window{") }
        if (currentFocus.isNotEmpty()) return currentFocus.any { mentionsTarget(it, target) }

        val resumed = lines.filter {
            it.contains("topResumedActivity") ||
                it.contains("mResumedActivity") ||
                it.contains("ResumedActivity:")
        }.filter { it.contains("ActivityRecord{") }
        if (resumed.isNotEmpty()) return resumed.any { mentionsTarget(it, target) }

        val focused = lines.filter { it.contains("mFocusedApp") && it.contains("ActivityRecord{") }
        return if (focused.isEmpty()) null else focused.any { mentionsTarget(it, target) }
    }
}
