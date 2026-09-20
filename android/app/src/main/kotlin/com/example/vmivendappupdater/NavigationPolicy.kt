package com.example.vmivendappupdater

/** Android's rooted per-package immersive policy also covers older iVend APKs. */
internal object NavigationPolicy {
    fun merge(existing: String, packages: List<String>): String {
        val entries = existing.trim().takeUnless { it == "null" }.orEmpty()
            .split(':').filter { it.isNotBlank() }
        val navigation = entries.filter { it.startsWith("immersive.navigation=") }
            .flatMap { it.substringAfter('=').split(',') }
            .filter { it.isNotBlank() && it !in packages.map { pkg -> "-$pkg" } }
        return (entries.filterNot { it.startsWith("immersive.navigation=") } +
            ("immersive.navigation=" + (navigation + packages).distinct().joinToString(",")))
            .joinToString(":")
    }

    fun apply(context: android.content.Context, target: String) {
        val current = RootShell.run("settings get global policy_control", 5)
        if (!current.succeeded) {
            UpdaterLog.append(context, "Could not read navigation policy: ${current.output.take(200)}")
            return
        }
        val value = current.output.lineSequence().lastOrNull()?.trim().orEmpty()
        val desired = merge(value, listOf(target, context.packageName))
        if (desired == value) return
        val result = RootShell.run("settings put global policy_control ${RootShell.quote(desired)}", 5)
        UpdaterLog.append(context, if (result.succeeded)
            "Navigation hiding requested for iVend and updater. Swipe can temporarily reveal controls."
            else "Could not hide navigation: ${result.output.take(200)}")
    }
}
