# FocusLock: handoff notes for Claude Code

## Goal (from the owner)
An Android app, for the owner's own phone, that blocks Instagram, YouTube and all games so they cannot open them
"no matter what" once active. The app itself must not be uninstallable or force-stoppable.

## Owner's requirements
- Simple UI: add apps, pick a time, press start. Once running, nothing can stop it.
- Also an automatic mode during working hours, with an optional daily limit per app.
- Some apps must be lockable for months.
- If a locked game is uninstalled and installed again, it must be blocked again.
- "Don't leave any loopholes."
- Android only. Owner wants an installable APK to test directly on their phone.

## Design (already implemented in app/src/main/java/com/focuslock)
- Device Owner app (DevicePolicyManager). Blocks with setPackagesSuspended; closes the web side with Chrome/Edge
  URLBlocklist managed config and suspends other browsers while Instagram/YouTube are blocked.
- Config.kt / Store.kt: working-hours config. Stricter changes apply at once, weaker ones wait 24h.
  Hard locks are stored per PACKAGE NAME with an end time and can only be extended (survive uninstall/reinstall).
- Enforcer.kt: one idempotent enforce() called from UI, alarms, boot, a 15-min JobScheduler job and WatchService.
  Fails closed if usage access is missing. lockdown() (uninstall block, no force stop/clear data, locked clock,
  ADB off, factory reset/safe mode/user/profile restrictions) only runs once the user presses "Arm".
- WatchService.kt: foreground service listening for package added/replaced so reinstalled apps are re-suspended at once.
- Optional partner unlock via Secrets.PARTNER_SHA256 in Config.kt (empty = no way out).
- Test mode: before arming, "Remove device owner (test mode only)" undoes everything. Armed = permanent.

## Status
- All Kotlin type-checks with kotlinc 1.9.24 against android.jar (API 34): no errors (re-checked after the review below).
- NOT yet built with Gradle (the review sandbox could not reach Google's Maven / SDK), NOT run on a device.
- Review done (Oct 2026):
  - URLBlocklist as String[]: OK, Chrome's PolicyConverter accepts String[] for list policies.
  - specialUse FGS: OK (type + subtype property declared; device owners are exempt from background-start limits).
  - setUserControlDisabledPackages: OK on API 30+ (greys out Force stop / Clear storage).
  - usageSince: fixed. Now counts apps already open when the window starts, handles reboots, and clips to the window.
  - Fixed fail-open: other browsers are now suspended even if Chrome/Edge is not installed.
  - Added screen-on/unlock/time-change re-checks and a 30 s tick while a daily limit is running (alarms are inexact).
  - Release signing via keystore.properties (CI debug keys differ per run, so armed devices could never be updated).

## Your tasks
1. Install JDK 17 and the Android SDK (platform 34, build-tools). Build with `gradle assembleDebug`
   (no wrapper jar is included; use Gradle 8.7 or generate a wrapper).
2. Fix any build errors. Output: app/build/outputs/apk/debug/app-debug.apk.
3. Review runtime risks: Chrome URLBlocklist as a String[] managed config, the specialUse foreground service,
   setUserControlDisabledPackages, and usage-event counting in Enforcer.usageSince.

## Safety rules
- Do NOT run `adb shell dpm set-device-owner` or arm the lockdown yourself. The owner does the device steps,
  on a freshly reset phone with no accounts, in test mode first.
- Never add any code path that removes the lock, device ownership or restrictions after arming.
- Keep the in-app test-mode removal gated on !armed.
