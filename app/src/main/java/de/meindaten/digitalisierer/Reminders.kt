package de.meindaten.digitalisierer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Erinnerungen an Fristen. Die App gibt nur Datum + kurzen Text (ohne Namen/Beträge) weiter,
 * damit auf dem Sperrbildschirm nichts Vertrauliches steht.
 */
object Reminders {
    private const val CHANNEL = "fristen"
    private const val PREFS = "reminders"

    fun set(ctx: Context, json: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = runCatching { JSONArray(json) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() } ?: emptySet()
        val shown = p.getStringSet("shown", emptySet())!!.filter { it in ids }.toSet()
        p.edit().putString("items", json).putStringSet("shown", shown).apply()
        WorkManager.getInstance(ctx).enqueueUniqueWork("reminder-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build())
    }

    fun check(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(p.getString("items", "[]")) }.getOrNull() ?: return
        val shown = p.getStringSet("shown", emptySet())!!.toMutableSet()
        val today = LocalDate.now().toString()
        val due = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("d") <= today && it.optString("id") !in shown }
        if (due.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fristen & Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        for (o in due) {
            val id = o.getString("id")
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Mein Daten-Digitalisierer")
                .setContentText(o.optString("t"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(o.optString("t")))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
            shown.add(id)
        }
        p.edit().putStringSet("shown", shown).apply()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { Reminders.check(applicationContext); return Result.success() }
}
