package com.focuslock

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * One idempotent function decides what must be blocked right now and makes the phone match it.
 * It runs from the UI, alarms, boot and a 15-minute backstop job.
 */
object Enforcer {
    private val POLICY_BROWSERS = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.microsoft.emmx")

    private val ALWAYS_SAFE = setOf(
        "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.vending",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.googlequicksearchbox",
        "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom",
        "com.google.android.apps.messaging", "com.android.mms", "com.google.android.permissioncontroller"
    )

    /** App -> websites that must also be closed while the app is blocked. */
    private val WEB = mapOf(
        "com.instagram.android" to listOf("instagram.com"),
        "com.instagram.lite" to listOf("instagram.com"),
        "com.google.android.youtube" to listOf("youtube.com", "youtu.be"),
        "com.google.android.apps.youtube.music" to listOf("youtube.com"),
        "com.google.android.apps.youtube.mango" to listOf("youtube.com")
    )

    fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun protectedPackages(c: Context): Set<String> {
        val s = HashSet<String>(ALWAYS_SAFE)
        s.add(c.packageName)
        try {
            (c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { s.add(it) }
        } catch (e: Exception) {
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(c)?.let { s.add(it) }
        } catch (e: Exception) {
        }
        val home = c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )
        home?.activityInfo?.packageName?.let { s.add(it) }
        return s
    }

    /** (package, label) for apps the user can pick. */
    fun launchableApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val safe = protectedPackages(c)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in safe }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(a: ApplicationInfo): Boolean =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    fun installedGames(c: Context): Set<String> {
        val out = HashSet<String>()
        for (a in c.packageManager.getInstalledApplications(0)) {
            if (isGame(a) && (a.flags and ApplicationInfo.FLAG_SYSTEM) == 0) out.add(a.packageName)
        }
        return out
    }

    /** Everything the working-hours config covers: chosen apps plus every installed game (including new ones). */
    fun targets(c: Context, cfg: Config): Set<String> {
        val out = HashSet<String>(cfg.blocked)
        if (cfg.blockGames) out.addAll(installedGames(c))
        out.removeAll(protectedPackages(c))
        return out
    }

    private fun installed(c: Context, p: String): Boolean = try {
        c.packageManager.getPackageInfo(p, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun otherBrowsers(c: Context): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val all = c.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        return all.toSet() - POLICY_BROWSERS.toSet() - protectedPackages(c)
    }

    /** Foreground milliseconds per package since [since], from usage events. Null if usage access is missing. */
    private fun usageSince(c: Context, since: Long, now: Long): Map<String, Long>? {
        if (!hasUsageAccess(c)) return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Look back before the window so an app that was already open when working hours began is still counted
        // (its RESUMED event happened before `since`). Every interval is clipped to [since, now].
        val events = usm.queryEvents(since - LOOKBACK_MS, now) ?: return null
        val e = UsageEvents.Event()
        val started = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        fun close(p: String, end: Long) {
            val s = started.remove(p) ?: return
            val from = maxOf(s, since)
            if (end > from) total[p] = (total[p] ?: 0L) + (end - from)
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                // Keep the earliest start if a second activity of the same app resumes before the first pauses.
                UsageEvents.Event.ACTIVITY_RESUMED -> started.putIfAbsent(e.packageName, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> close(e.packageName, e.timeStamp)
                // A reboot ends every session; without this an app open at shutdown would count until now.
                UsageEvents.Event.DEVICE_SHUTDOWN -> for (p in started.keys.toList()) close(p, e.timeStamp)
            }
        }
        for (p in started.keys.toList()) close(p, now)
        return total
    }

    private const val LOOKBACK_MS = 12L * 60 * 60 * 1000

    @Synchronized
    fun enforce(context: Context) {
        val c = context.applicationContext
        val store = Store(c)
        val now = System.currentTimeMillis()
        store.promotePending(now)
        Scheduler.ensureJob(c)
        if (!isOwner(c)) return
        WatchService.ensure(c)
        if (store.armed) lockdown(c)

        val cfg = store.config
        val toSuspend = HashSet<String>()

        // Hard locks are tied to the package name, so uninstalling and reinstalling an app changes nothing.
        for ((p, until) in store.locks) if (until > now) toSuspend.add(p)
        if (store.gamesLockUntil > now) toSuspend.addAll(installedGames(c))
        toSuspend.removeAll(protectedPackages(c))

        // Automatic working-hours blocking.
        val scheduled = cfg.isActive(now)
        var anyUnder = false
        if (scheduled) {
            val targets = targets(c, cfg)
            if (cfg.limitMin <= 0) {
                toSuspend.addAll(targets)
            } else {
                val usage = usageSince(c, cfg.windowStart(now), now)
                for (p in targets) {
                    if (p in toSuspend) continue
                    val used = usage?.get(p) ?: 0L
                    // No usage access = fail closed: treat the limit as used up.
                    if (usage == null || used >= cfg.limitMin * 60_000L) toSuspend.add(p) else anyUnder = true
                }
            }
        }
        apply(c, store, cfg, toSuspend)

        var next = Long.MAX_VALUE
        for (u in store.locks.values) if (u > now) next = minOf(next, u)
        if (store.gamesLockUntil > now) next = minOf(next, store.gamesLockUntil)
        if (store.pending != null && store.pendingAt > now) next = minOf(next, store.pendingAt)
        cfg.nextEdge(now)?.let { next = minOf(next, it) }
        // While an app is under its daily limit, WatchService re-checks every 30 s (alarms alone are inexact).
        WatchService.fastTick = scheduled && anyUnder
        if (scheduled && anyUnder) next = minOf(next, now + 30_000L)
        if (next != Long.MAX_VALUE) Scheduler.alarmAt(c, next + 1000L)
    }

    private fun apply(c: Context, store: Store, cfg: Config, wanted: Set<String>) {
        val d = dpm(c)
        val a = admin(c)

        val domains = HashSet<String>()
        for (p in wanted) WEB[p]?.let { domains.addAll(it) }

        val sus = HashSet<String>(wanted)
        // Websites are closed through Chrome/Edge policy; every other browser is suspended so it can't be a way around.
        // Fail closed: if neither Chrome nor Edge is installed, every browser is suspended while these sites are blocked.
        if (domains.isNotEmpty()) sus.addAll(otherBrowsers(c))

        val release = store.suspended - sus
        try {
            if (release.isNotEmpty()) d.setPackagesSuspended(a, release.toTypedArray(), false)
        } catch (e: Exception) {
        }
        try {
            if (sus.isNotEmpty()) d.setPackagesSuspended(a, sus.toTypedArray(), true)
        } catch (e: Exception) {
        }
        store.suspended = sus

        for (b in POLICY_BROWSERS) {
            try {
                val bundle = Bundle()
                if (domains.isNotEmpty()) bundle.putStringArray("URLBlocklist", domains.toTypedArray())
                d.setApplicationRestrictions(a, b, bundle)
            } catch (e: Exception) {
            }
        }

        // Optional: while anything is blocked, no new apps (alternative clients, games) can be installed.
        val on = sus.isNotEmpty() && cfg.blockInstalls
        setRestriction(c, UserManager.DISALLOW_INSTALL_APPS, on)
        setRestriction(c, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, on)
    }

    /** TEST MODE ONLY: undoes everything and gives up device ownership. Refuses once lockdown is armed. */
    @Suppress("DEPRECATION")
    fun removeTestOwner(c: Context): Boolean {
        val store = Store(c)
        if (store.armed || !isOwner(c)) return false
        val d = dpm(c)
        val a = admin(c)
        try { if (store.suspended.isNotEmpty()) d.setPackagesSuspended(a, store.suspended.toTypedArray(), false) } catch (e: Exception) { }
        setRestriction(c, UserManager.DISALLOW_INSTALL_APPS, false)
        setRestriction(c, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false)
        for (b in POLICY_BROWSERS) {
            try { d.setApplicationRestrictions(a, b, Bundle()) } catch (e: Exception) { }
        }
        store.clearLocks()
        store.suspended = emptySet()
        c.stopService(Intent(c, WatchService::class.java))
        try { d.clearDeviceOwnerApp(c.packageName) } catch (e: Exception) { return false }
        return true
    }

    private fun setRestriction(c: Context, key: String, on: Boolean) {
        try {
            if (on) dpm(c).addUserRestriction(admin(c), key) else dpm(c).clearUserRestriction(admin(c), key)
        } catch (e: Exception) {
        }
    }

    /** Applied permanently once armed. There is no code path that removes any of this. */
    private fun lockdown(c: Context) {
        val d = dpm(c)
        val a = admin(c)
        try { d.setUninstallBlocked(a, c.packageName, true) } catch (e: Exception) { }
        try { d.setUserControlDisabledPackages(a, listOf(c.packageName)) } catch (e: Exception) { }   // no force stop / clear data
        try { d.setAutoTimeEnabled(a, true); d.setAutoTimeZoneEnabled(a, true) } catch (e: Exception) { }
        try {
            d.setPermissionGrantState(
                a, c.packageName, Manifest.permission.PACKAGE_USAGE_STATS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: Exception) { }
        try { d.setGlobalSetting(a, Settings.Global.ADB_ENABLED, "0") } catch (e: Exception) { }
        try {
            d.setPermissionGrantState(
                a, c.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: Exception) { }
        val restrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            "no_add_clone_profile"
        )
        for (r in restrictions) setRestriction(c, r, true)
    }
}
