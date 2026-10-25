package com.focuslock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object Scheduler {
    /** Backstop: re-checks everything every 15 minutes, survives reboots. */
    fun ensureJob(c: Context) {
        try {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(1) == null) {
                js.schedule(
                    JobInfo.Builder(1, ComponentName(c, EnforceJobService::class.java))
                        .setPeriodic(15 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                )
            }
        } catch (e: Exception) {
        }
    }

    fun alarmAt(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
