package com.focuslock

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private var draft = Config()
    private var lockPick: Set<String> = emptySet()
    private var lockGames = true
    private var lockDur = 2
    private var status: TextView? = null
    private var limitField: EditText? = null
    private var lastLocked = false
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val locked = store.anyLockActive(System.currentTimeMillis())
            if (locked != lastLocked) render() else status?.text = statusText()
            ui.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Enforcer.enforce(this)
        draft = store.draft()
        render()
        ui.postDelayed(tick, 15_000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------- helpers ----------
    private fun tv(s: String, size: Float = 16f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, 16, 0, 8)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun fmt(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
    private fun hm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun syncLimit() {
        limitField?.let { draft = draft.copy(limitMin = it.text.toString().toIntOrNull() ?: draft.limitMin) }
    }

    private fun statusText(): String {
        val now = System.currentTimeMillis()
        val cfg = store.config
        val parts = ArrayList<String>()
        if (store.anyLockActive(now)) parts.add("Locks are active")
        if (cfg.isActive(now)) parts.add("Working hours active until ${hm(cfg.endMin)}")
        val sb = StringBuilder(if (parts.isEmpty()) "Not blocking right now" else parts.joinToString("\n"))
        if (Enforcer.isOwner(this) && !store.armed) sb.append("\nLockdown not armed yet (test mode)")
        if (store.pending != null) sb.append("\nWeaker settings take effect ${fmt(store.pendingAt)}")
        return sb.toString()
    }

    // ---------- screens ----------
    private fun render() {
        syncLimit()
        limitField = null
        root.removeAllViews()
        lastLocked = store.anyLockActive(System.currentTimeMillis())

        root.addView(tv("FocusLock", 28f))
        val s = tv(statusText())
        status = s
        root.addView(s)

        if (!Enforcer.isOwner(this)) {
            root.addView(tv(
                "Not set up yet. On a freshly reset phone with no accounts signed in, enable USB debugging and run:\n\n" +
                    "adb shell dpm set-device-owner com.focuslock/.AdminReceiver"
            ))
            return
        }
        if (!store.armed) setupSection()
        if (lastLocked) activeLocksSection()
        lockSection()
        editorSection()
    }

    private fun setupSection() {
        root.addView(tv("Setup", 20f))
        val ok = Enforcer.hasUsageAccess(this)
        root.addView(tv(if (ok) "Usage access: granted" else "Usage access: missing (needed for daily limits)"))
        if (!ok) root.addView(btn("Grant usage access") {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })
        root.addView(btn("Remove device owner (test mode only)") {
            AlertDialog.Builder(this)
                .setTitle("Remove device owner?")
                .setMessage("Undoes all blocks and locks and removes FocusLock's control. This only works before lockdown is armed.")
                .setPositiveButton("Remove") { _, _ ->
                    toast(if (Enforcer.removeTestOwner(this)) "Removed. You can uninstall the app now." else "Could not remove")
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        root.addView(btn("Arm permanent lockdown") {
            if (!Enforcer.hasUsageAccess(this)) {
                toast("Grant usage access first")
                return@btn
            }
            AlertDialog.Builder(this)
                .setTitle("Arm lockdown?")
                .setMessage(
                    "After this the app cannot be uninstalled, force-stopped or removed, USB debugging is switched off, " +
                        "and the date and time are locked. Only a factory reset from recovery undoes it. " +
                        "Test everything with an Android Studio build first."
                )
                .setPositiveButton("Arm") { _, _ ->
                    store.armed = true
                    Enforcer.enforce(this)
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    private fun activeLocksSection() {
        val now = System.currentTimeMillis()
        root.addView(tv("Locked right now", 20f))
        val rows = store.locks.entries.filter { it.value > now }.sortedBy { it.value }
        val shown = rows.take(12)
        val sb = StringBuilder()
        if (store.gamesLockUntil > now) sb.append("• All games until ${fmt(store.gamesLockUntil)}\n")
        for (e in shown) sb.append("• ${label(e.key)} until ${fmt(e.value)}\n")
        if (rows.size > shown.size) sb.append("• and ${rows.size - shown.size} more")
        root.addView(tv(sb.toString().trim()))
        root.addView(tv("Locks cannot be shortened or removed, only extended. Uninstalling and reinstalling an app does not help: it is blocked again immediately."))
        if (Secrets.PARTNER_SHA256.isNotEmpty()) {
            val code = EditText(this).apply {
                hint = "Partner code"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            root.addView(code)
            root.addView(btn("Partner unlock (ends all locks)") {
                if (sha256(code.text.toString()) == Secrets.PARTNER_SHA256) {
                    store.clearLocks()
                    Enforcer.enforce(this)
                    render()
                } else {
                    toast("Wrong code")
                }
            })
        }
    }

    private fun lockSection() {
        root.addView(tv("Lock apps now", 20f))
        root.addView(btn("Choose apps to lock (${lockPick.size} selected)") {
            pickApps("Apps to lock", lockPick) { lockPick = it; render() }
        })
        root.addView(CheckBox(this).apply {
            text = "Also lock all games (including ones installed later)"
            isChecked = lockGames
            setOnCheckedChangeListener { _, v -> lockGames = v }
        })
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, DUR_LABELS)
            setSelection(lockDur)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    lockDur = position
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(sp)
        root.addView(btn("Start lock") {
            if (lockPick.isEmpty() && !lockGames) {
                toast("Choose at least one app, or tick \"Also lock all games\".")
                return@btn
            }
            val mins = DUR_MIN[lockDur]
            confirmLock(System.currentTimeMillis() + mins * 60_000L, mins >= 10080)
        })
    }

    private fun confirmLock(until: Long, needTyping: Boolean) {
        val names = lockPick.take(5).joinToString(", ") { label(it) } +
            (if (lockPick.size > 5) " and ${lockPick.size - 5} more" else "")
        val what = listOfNotNull(
            if (lockPick.isNotEmpty()) names else null,
            if (lockGames) "all games" else null
        ).joinToString(" + ")
        val input = EditText(this).apply { hint = "Type LOCK to confirm" }
        val b = AlertDialog.Builder(this)
            .setTitle("Start lock?")
            .setMessage("$what will be blocked until ${fmt(until)}. This cannot be shortened or undone.")
        if (needTyping) b.setView(input)
        b.setPositiveButton("Lock") { _, _ ->
            if (needTyping && input.text.toString().trim() != "LOCK") {
                toast("Not locked. You have to type LOCK.")
                return@setPositiveButton
            }
            val games = if (lockGames) Enforcer.installedGames(this) else emptySet()
            store.addLock(lockPick + games, lockGames, until)
            lockPick = emptySet()
            Enforcer.enforce(this)
            render()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun editorSection() {
        root.addView(tv("Working hours (automatic)", 20f))
        root.addView(btn("Apps for working hours (${draft.blocked.size} selected)") {
            pickApps("Apps to block in working hours", draft.blocked) { draft = draft.copy(blocked = it); render() }
        })
        root.addView(CheckBox(this).apply {
            text = "Block all games"
            isChecked = draft.blockGames
            setOnCheckedChangeListener { _, v -> draft = draft.copy(blockGames = v) }
        })
        root.addView(CheckBox(this).apply {
            text = "Block automatically during these hours"
            isChecked = draft.scheduleOn
            setOnCheckedChangeListener { _, v -> draft = draft.copy(scheduleOn = v) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0..6) {
            row.addView(CheckBox(this).apply {
                text = labels[i]
                isChecked = (draft.days and (1 shl i)) != 0
                setOnCheckedChangeListener { _, v ->
                    draft = draft.copy(days = if (v) draft.days or (1 shl i) else draft.days and (1 shl i).inv())
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        root.addView(btn("From ${hm(draft.startMin)}") { pickTime(true) })
        root.addView(btn("To ${hm(draft.endMin)}") { pickTime(false) })
        root.addView(tv("Daily limit per app, in minutes (0 = fully blocked during these hours)"))
        val f = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(draft.limitMin.toString())
        }
        limitField = f
        root.addView(f)
        root.addView(CheckBox(this).apply {
            text = "No installing new apps while anything is blocked"
            isChecked = draft.blockInstalls
            setOnCheckedChangeListener { _, v -> draft = draft.copy(blockInstalls = v) }
        })
        root.addView(btn("Save settings") { saveDraft() })
        root.addView(tv("Making blocking weaker (fewer apps, shorter hours, bigger limit, schedule off, installs allowed) only takes effect after 24 hours. Making it stricter applies immediately."))
    }

    // ---------- actions ----------
    private fun pickApps(title: String, initial: Set<String>, onDone: (Set<String>) -> Unit) {
        syncLimit()
        val apps = Enforcer.launchableApps(this)
        val checked = BooleanArray(apps.size) { apps[it].first in initial }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("OK") { _, _ ->
                val sel = HashSet<String>()
                for (i in apps.indices) if (checked[i]) sel.add(apps[i].first)
                val hidden = initial.filter { p -> apps.none { it.first == p } }
                onDone(sel + hidden)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTime(start: Boolean) {
        syncLimit()
        val m = if (start) draft.startMin else draft.endMin
        TimePickerDialog(this, { _, h, mi ->
            draft = if (start) draft.copy(startMin = h * 60 + mi) else draft.copy(endMin = h * 60 + mi)
            render()
        }, m / 60, m % 60, true).show()
    }

    private fun saveDraft() {
        limitField?.let { draft = draft.copy(limitMin = it.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 0) }
        if (draft.scheduleOn && draft.endMin <= draft.startMin) {
            toast("The end time must be after the start time.")
            return
        }
        val applied = store.submit(draft, System.currentTimeMillis())
        Enforcer.enforce(this)
        toast(if (applied) "Saved" else "Saved. Weaker settings apply after 24 hours.")
        draft = store.draft()
        render()
    }

    private companion object {
        val DUR_LABELS = arrayOf(
            "15 minutes", "30 minutes", "1 hour", "2 hours", "4 hours", "8 hours", "12 hours", "24 hours",
            "3 days", "1 week", "2 weeks", "1 month", "3 months", "6 months", "1 year"
        )
        val DUR_MIN = intArrayOf(
            15, 30, 60, 120, 240, 480, 720, 1440,
            4320, 10080, 20160, 43200, 129600, 259200, 525600
        )
    }
}
