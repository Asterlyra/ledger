package com.atlas.ledger

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class LedgerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Stop the previous release's automatic actual-spending job and dismiss its notifications.
        WorkManager.getInstance(this).cancelUniqueWork("recurring-ledger-check")
        NotificationManagerCompat.from(this).cancelAll()
    }
}

// Keep these class names so any job or PendingIntent queued before an upgrade is harmless.
class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
class RecurringCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("occurrence_id")?.let { NotificationManagerCompat.from(context).cancel(it.hashCode()) }
    }
}
