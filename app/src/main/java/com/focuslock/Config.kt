package com.focuslock

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Never put a real partner code here unless a friend chose it. Empty = no way out at all. */
object Secrets {
    /** SHA-256 hex of a code only your trusted person knows. Empty disables partner unlock. */
    const val PARTNER_SHA256 = ""
}

data class Config(
    val blocked: Set<String> = emptySet(),
    val blockGames: Boolean = true,
    val scheduleOn: Boolean = false,
    val days: Int = 62,              // bit0 = Sunday ... bit6 = Saturday; 62 = Mon-Fri
    val startMin: Int = 9 * 60,
    val endMin: Int = 17 * 60,
    val limitMin: Int = 0,           // 0 = fully blocked during working hours
    val blockInstalls: Boolean = false   // no new app installs while anything is blocked
) {
    fun toJson(): String = JSONObject()
        .put("blocked", JSONArray(blocked.toList()))
        .put("blockGames", blockGames)
        .put("scheduleOn", scheduleOn)
        .put("days", days)
        .put("startMin", startMin)
        .put("endMin", endMin)
        .put("limitMin", limitMin)
        .put("blockInstalls", blockInstalls)
        .toString()

    fun isActive(now: Long): Boolean {
        if (!scheduleOn || endMin <= startMin) return false
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if ((days and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1))) == 0) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m in startMin until endMin
    }

    fun windowStart(now: Long): Long = at(now, startMin)

    fun nextEdge(now: Long): Long? {
        if (!scheduleOn || days == 0 || endMin <= startMin) return null
        for (offset in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            if ((days and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1))) == 0) continue
            val s = at(c.timeInMillis, startMin)
            val e = at(c.timeInMillis, endMin)
            if (s > now) return s
            if (e > now) return e
        }
        return null
    }

    /** True if this config blocks at least as much as [old] in every respect. */
    fun tightens(old: Config): Boolean {
        val scheduleOk = !old.scheduleOn || (
            scheduleOn &&
                (old.days and days) == old.days &&
                startMin <= old.startMin && endMin >= old.endMin &&
                limitMin <= old.limitMin
            )
        return blocked.containsAll(old.blocked) &&
            (blockGames || !old.blockGames) &&
            (blockInstalls || !old.blockInstalls) &&
            scheduleOk
    }

    companion object {
        fun at(base: Long, minute: Int): Long {
            val c = Calendar.getInstance().apply { timeInMillis = base }
            c.set(Calendar.HOUR_OF_DAY, minute / 60)
            c.set(Calendar.MINUTE, minute % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        fun fromJson(s: String?): Config {
            if (s == null) return Config()
            return try {
                val o = JSONObject(s)
                val a = o.getJSONArray("blocked")
                Config(
                    blocked = (0 until a.length()).map { a.getString(it) }.toSet(),
                    blockGames = o.getBoolean("blockGames"),
                    scheduleOn = o.getBoolean("scheduleOn"),
                    days = o.getInt("days"),
                    startMin = o.getInt("startMin"),
                    endMin = o.getInt("endMin"),
                    limitMin = o.getInt("limitMin"),
                    blockInstalls = o.optBoolean("blockInstalls", false)
                )
            } catch (e: Exception) {
                Config()
            }
        }
    }
}
