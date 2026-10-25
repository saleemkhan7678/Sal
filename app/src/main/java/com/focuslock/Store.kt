package com.focuslock

import android.content.Context
import org.json.JSONObject

class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("focuslock", Context.MODE_PRIVATE)

    var armed: Boolean
        get() = p.getBoolean("armed", false)
        set(v) { p.edit().putBoolean("armed", v).apply() }

    var suspended: Set<String>
        get() = p.getStringSet("suspended", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("suspended", HashSet(v)).apply() }

    /**
     * Hard locks, keyed by PACKAGE NAME with an end time (wall clock; the clock is locked once armed).
     * Because they are keyed by name, uninstalling and reinstalling an app changes nothing: it is blocked again.
     * Locks can only be added or extended, never shortened.
     */
    val locks: Map<String, Long>
        get() {
            val s = p.getString("locks", null) ?: return emptyMap()
            return try {
                val o = JSONObject(s)
                o.keys().asSequence().associateWith { o.getLong(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** While in the future, every installed game (including ones installed later) is locked. */
    val gamesLockUntil: Long get() = p.getLong("gamesLock", 0L)

    fun addLock(pkgs: Collection<String>, games: Boolean, until: Long) {
        val cur = HashMap<String, Long>(locks)
        for (pk in pkgs) cur[pk] = maxOf(cur[pk] ?: 0L, until)
        val o = JSONObject()
        for ((k, v) in cur) o.put(k, v)
        val e = p.edit().putString("locks", o.toString())
        if (games) e.putLong("gamesLock", maxOf(gamesLockUntil, until))
        e.apply()
    }

    /** Only used by the optional partner code. */
    fun clearLocks() {
        p.edit().remove("locks").remove("gamesLock").apply()
    }

    fun anyLockActive(now: Long): Boolean = locks.values.any { it > now } || gamesLockUntil > now

    val config: Config get() = Config.fromJson(p.getString("config", null))
    val pending: Config? get() = p.getString("pending", null)?.let { Config.fromJson(it) }
    val pendingAt: Long get() = p.getLong("pendingAt", 0L)

    fun draft(): Config = pending ?: config

    /** Stricter settings apply at once. Anything weaker waits 24 hours. Returns true if applied now. */
    fun submit(new: Config, now: Long): Boolean {
        val old = config
        return if (new == old || new.tightens(old)) {
            p.edit().putString("config", new.toJson()).remove("pending").remove("pendingAt").apply()
            true
        } else {
            p.edit().putString("pending", new.toJson()).putLong("pendingAt", now + DELAY_MS).apply()
            false
        }
    }

    fun promotePending(now: Long) {
        val pd = pending ?: return
        if (now >= pendingAt) {
            p.edit().putString("config", pd.toJson()).remove("pending").remove("pendingAt").apply()
        }
    }

    // ---- YouTube Shorts blocking: on at once, off only 24 h after asking. ----
    val shortsOn: Boolean get() = p.getBoolean("shortsOn", false)

    /** When a requested "off" takes effect (0 = no request). */
    val shortsOffAt: Long get() = p.getLong("shortsOffAt", 0L)

    fun shortsTurnOn() {
        p.edit().putBoolean("shortsOn", true).remove("shortsOffAt").apply()
    }

    fun shortsRequestOff(now: Long) {
        if (shortsOn && shortsOffAt == 0L) p.edit().putLong("shortsOffAt", now + DELAY_MS).apply()
    }

    fun promoteShorts(now: Long) {
        if (shortsOn && shortsOffAt in 1..now) p.edit().putBoolean("shortsOn", false).remove("shortsOffAt").apply()
    }

    companion object {
        const val DELAY_MS = 24L * 60 * 60 * 1000
    }
}
