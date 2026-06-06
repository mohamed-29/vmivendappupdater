package com.example.vmivendappupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class CheckUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CHECK_UPDATE) {
            Log.i("CheckUpdateReceiver", "Received check update broadcast — enqueuing immediate check")

            scheduleUpdates(context)

            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    companion object {
        const val ACTION_CHECK_UPDATE = "com.example.vmivendappupdater.CHECK_UPDATE"
    }
}
