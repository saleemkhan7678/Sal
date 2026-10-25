# FocusLock

Device Owner app for Android 11+ that blocks chosen apps, all games, and their websites.

## Test first (removable)
1. Factory reset a spare phone (or the emulator). Skip adding any Google account.
2. Enable USB debugging.
3. Open this folder in Android Studio and Run. Studio builds a testOnly APK.
4. `adb shell dpm set-device-owner com.focuslock/.AdminReceiver`
5. Try sessions and working hours. Do NOT press "Arm permanent lockdown" yet.
6. To remove a test build: `adb shell dpm remove-active-admin com.focuslock/.AdminReceiver`

## Signing (do this before arming)
Once armed, FocusLock can't be uninstalled, so every update must be signed with the same key. Create one in
Android Studio (Build > Generate Signed Bundle / APK > Create new) and add `keystore.properties` in this folder:
```
storeFile=focuslock.jks
storePassword=...
keyAlias=focuslock
keyPassword=...
```
Keep the .jks somewhere safe. Note: whoever holds this key can push an update that changes the app, so if you want
no way out at all, delete the key after the final build (you then can't fix bugs either).

## Real use
Build > Generate Signed Bundle / APK (or `./gradlew assembleRelease`), install it on the reset phone, run the
`set-device-owner` command, grant usage access, then press "Arm permanent lockdown".

## Locks vs working hours
- **Lock apps now:** pick apps (and optionally all games) and a duration from 15 minutes up to 1 year. Locks are stored by
  package name and can only be extended. Locks of a week or more need you to type LOCK.
- **Working hours:** automatic blocking with an optional daily limit. Weakening it takes 24 hours.
- Uninstalling and reinstalling a locked app or game does not help: a background watcher re-suspends it the moment it is installed.

## Optional partner unlock
Set `Secrets.PARTNER_SHA256` (Config.kt) to the SHA-256 of a code only a friend knows. Leave empty for no way out.

## Known gaps (not fixable from an app)
- Games are detected by the game flag/category in the app's manifest. Games that don't set it aren't caught by "all games";
  pick them by name, or turn on "No installing new apps while anything is blocked".
- In-app browsers (WebView inside other apps, the Google app) can still open instagram.com / youtube.com.
- Cloud-gaming websites and Play Instant "Try now" games aren't covered.
- A factory reset from recovery still removes everything.
