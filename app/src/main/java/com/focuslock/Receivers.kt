package com.focuslock

import android.app.admin.DeviceAdminReceiver
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Enforcer.enforce(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "FocusLock cannot be disabled."
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Enforcer.enforce(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Enforcer.enforce(context)
    }
}

class EnforceJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Enforcer.enforce(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
