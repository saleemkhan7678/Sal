package com.focuslock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches only the YouTube app (see res/xml/shorts_blocker.xml). When a Shorts screen is showing it presses Back,
 * so normal videos keep working but Shorts can't be watched. Enforcer suspends YouTube entirely if this service is
 * switched off while Shorts blocking is on, so turning it off is not a way around it.
 */
class ShortsBlocker : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastBack = 0L
    private var lastToast = 0L

    private val check = Runnable {
        pending = false
        if (!Store(this).shortsOn) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        if (root.packageName?.toString() !in YOUTUBE) return@Runnable
        if (!isShorts(root)) return@Runnable
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 700) return@Runnable
        lastBack = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (now - lastToast > 4000) {
            lastToast = now
            Toast.makeText(this, "Shorts are blocked by FocusLock", Toast.LENGTH_SHORT).show()
        }
        // Back from a Shorts link can land on another Short; look again shortly after.
        schedule(500)
    }

    private fun schedule(delay: Long) {
        if (pending) return
        pending = true
        handler.postDelayed(check, delay)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in YOUTUBE) return
        schedule(150)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Enforcer.enforce(this)   // lifts the fail-closed YouTube suspension as soon as the service is on
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun isShorts(root: AccessibilityNodeInfo): Boolean {
        for (pkg in YOUTUBE) for (id in SHORTS_IDS) {
            if (root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isVisibleToUser }) return true
        }
        // The Shorts tab in the bottom bar is selected (covers layouts whose view ids changed).
        return root.findAccessibilityNodeInfosByText("Shorts").any { n ->
            n.isVisibleToUser && n.isSelected &&
                (n.contentDescription?.toString()?.trim().equals("Shorts", true) || n.text?.toString()?.trim().equals("Shorts", true))
        }
    }

    companion object {
        val YOUTUBE = setOf("com.google.android.youtube")

        /** View ids YouTube uses for the Shorts player. Several, because they change between app versions. */
        private val SHORTS_IDS = listOf(
            "reel_player_page_container",
            "reel_recycler",
            "reel_watch_player",
            "reel_watch_fragment_root",
            "reel_progress_bar",
            "shorts_player_container"
        )

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, ShortsBlocker::class.java)
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
