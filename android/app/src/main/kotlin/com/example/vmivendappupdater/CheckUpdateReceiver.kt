package com.example.vmivendappupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CheckUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CHECK_UPDATE) {
            Log.i("CheckUpdateReceiver", "Received check update broadcast — enqueuing immediate check")

            enqueueImmediateUpdate(context)
        }
    }

    companion object {
        const val ACTION_CHECK_UPDATE = "com.example.vmivendappupdater.CHECK_UPDATE"
    }
}
