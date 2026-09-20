package com.example.vmivendappupdater

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Local app-to-updater coordination; package UID check avoids shared signing keys. */
class KioskControlProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        require(ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?.contains(ManagedTarget.DEFAULT_PACKAGE) == true) { "Only iVend may request maintenance" }
        val identity = Binder.clearCallingIdentity()
        try {
            return when (method) {
                "openSettings" -> {
                    check(!KioskSession.active(ctx, "install")) { "An update is being installed" }
                    UpdateForegroundService.start(ctx)
                    KioskSession.begin(ctx, "operator")
                    try {
                        KioskPolicy.configure(ctx, ManagedTarget.packageName(ctx), KioskPolicy.homeComponent(ctx))
                        // iVend launches Settings after this acknowledgment,
                        // while its activity still has foreground launch rights.
                    } catch (e: Exception) {
                        KioskSession.end(ctx, "operator")
                        throw e
                    }
                    Bundle().apply { putBoolean("ok", true) }
                }
                "returnToApp" -> {
                    if (KioskSession.active(ctx, "operator")) {
                        KioskSession.end(ctx, "operator")
                        KioskPolicy.configure(ctx, ManagedTarget.packageName(ctx), KioskPolicy.homeComponent(ctx))
                    }
                    Bundle().apply { putBoolean("ok", true) }
                }
                else -> throw IllegalArgumentException("Unknown kiosk operation")
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
