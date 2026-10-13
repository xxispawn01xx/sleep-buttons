# Sleep Buttons

A personal, offline Android app with two one-tap actions — **Start nap** and **Set melatonin reminder** — plus a small manual sleep log.
Kotlin · Jetpack Compose · AlarmManager · Room · DataStore. No network permission, accounts, analytics, or ads.

> **Build status — read this first.** The project was written in a sandbox without the Android SDK or access to Google's Maven repository, so the **Android app has not been compiled or run on a device.** The pure-Kotlin logic (`domain/`) and its 49 unit tests **were compiled and all passed** using a stand-alone Kotlin 2.0.21 compiler + JUnit 4.13.2. Expect to possibly fix a small compile error or two on first sync in Android Studio, and run the device checklist below before relying on it.

---

## 1. Plan and assumptions

**Layers** (kept small on purpose):

| Layer | Package | Notes |
|---|---|---|
| Pure logic (unit-tested) | `domain/` | nap offsets, duplicate-tap policy, next-occurrence, dose window, µg/mg, sleep math, circular clock stats, stale/clock-change rules. No Android imports. |
| Persistence | `data/` | Room: `alarm_state` (one row per slot), `nap_attempt`, `sleep_entry`, `dose_log`. DataStore: settings. |
| Scheduling | `alarm/` | `AlarmScheduler` interface → `AndroidAlarmScheduler`. `AlarmController` is the single owner of alarm state (mutex-serialised). Receivers, a ringing foreground service, a lock-screen activity. |
| UI | `ui/` | Three screens (Home, Log, Settings) switched by state — no navigation library. |

**Assumptions / defaults**
- minSdk 26 (Android 8), target/compile SDK 35. Package `app.sleepbuttons`.
- Exact alarms: `USE_EXACT_ALARM` on Android 13+, `SCHEDULE_EXACT_ALARM` (user-granted "Alarms & reminders") on Android 12/12L. Fine for a sideloaded personal app; Google Play restricts `USE_EXACT_ALARM` to alarm/calendar apps if you ever publish.
- Nap alarms use `setAlarmClock()` (top priority, Doze-exempt, shows as the system "next alarm"). The melatonin reminder uses `setExactAndAllowWhileIdle()` and posts a notification — it doesn't ring like an alarm.
- Nap timings are adjusted with − / + on the Home screen (or typed in Settings) and **save automatically** to DataStore, so they're there the next time you open the app. Changes apply to the next nap; a running nap keeps its times.
- A small **Cancel** button next to "Nap: get up" cancels just that alarm (e.g. still awake) and leaves the backup set. "End nap" still cancels both.
- Nap alarms ring for at most 10 minutes, then auto-silence (recorded as "timed out").
- **Stale alarms are never sounded late:** a nap alarm delivered more than 2 minutes late is recorded as missed (with a quiet notice); a melatonin reminder more than 15 minutes late is recorded as missed, with no catch-up.
- **Duplicate-dose window**: default 6:00 PM → 12:00 PM next day, start inclusive / end exclusive. The window starting on evening D covers D 18:00 → D+1 11:59. A dose at 11:50 PM and a reminder at 12:15 AM are in the *same* window; a dose last night does not block tonight. Times between noon and 6 PM are in no window, so Settings requires the reminder time to sit inside the window. The check runs when you tap the button (against the window the reminder would fire in) and again when it fires (if you logged a dose manually in between, the notification says so and offers no "Taken" button).
- Dose is never pre-filled. You type it, pick mg or mcg, see both units, and tick a confirmation that restates the conversion. Anything ≥100 mg is rejected as a likely units typo; values outside 50–100 mcg get a non-blocking "check the units" note. Nothing in the app changes the dose or time on its own.
- A scheduled reminder keeps the dose it was scheduled with. Editing the plan doesn't silently alter it; the app tells you to cancel and re-set.
- Sleep summaries: last 7 and 14 days, by final-wake time. Typical clock times use a **circular mean**; spread is the circular standard deviation (≤30 min "fairly consistent", ≤60 "somewhat variable", ≤120 "variable", above that "too spread out" and no typical time is shown). Durations are medians; "sleep-period estimate" unless every entry has awake minutes. Fewer than 3 entries → "Not enough data". A nap attempt counts as sleep only if you enter an onset.

**Time changes**
- *Manual clock change:* nap alarms keep elapsed-time semantics. Each nap alarm stores its `elapsedRealtime` target and the boot count; on `TIME_SET` it's re-registered so the remaining minutes stay the same.
- *Time-zone change:* nap alarms are absolute instants, so unaffected. The melatonin reminder stores its local date+time and is re-resolved in the new zone (8:30 PM stays 8:30 PM local).
- *Reboot:* `BOOT_COMPLETED` re-registers future alarms from stored wall-clock targets (elapsed time doesn't survive a reboot). Alarms restore after the **first unlock** (no direct-boot support in this MVP).
- The app also reconciles on every open: re-registers alarms (idempotent, same PendingIntent identity), marks stale ones missed, and shows **Not scheduled** if permission was revoked.

---

## 2. File tree

```
SleepButtons/
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradlew(.bat) · gradle/wrapper/
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/app/sleepbuttons/
        │   │   ├── SleepButtonsApp.kt          # tiny service locator, creates channels
        │   │   ├── domain/   AlarmSlot.kt  NapLogic.kt  ReminderLogic.kt  Dose.kt  SleepSummary.kt  AlarmTiming.kt
        │   │   ├── data/     Database.kt  SettingsRepository.kt
        │   │   ├── alarm/    AlarmScheduler.kt  AlarmController.kt  AlarmService.kt  AlarmActivity.kt
        │   │   │             Receivers.kt  Notifications.kt  Permissions.kt
        │   │   └── ui/       MainActivity.kt  MainViewModel.kt  HomeScreen.kt  SettingsScreen.kt
        │   │                 LogScreen.kt  Components.kt  Format.kt
        │   └── res/  values/ values-night/ drawable/ mipmap-anydpi-v26/
        └── test/java/app/sleepbuttons/domain/   (8 test classes, 49 tests)
```

Versions: AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Room 2.6.1, DataStore 1.1.1. Android Studio may offer newer versions; accepting them is fine.

---

## 3. Build and install a debug APK

1. Install **Android Studio** (current stable). Open the `SleepButtons` folder and let Gradle sync (it downloads the Gradle 8.11.1 distribution and dependencies).
2. Run unit tests: right-click `app/src/test` → *Run tests*, or `./gradlew :app:testDebugUnitTest`.
3. On the phone: *Settings → About phone →* tap *Build number* 7 times → *Developer options →* enable **USB debugging**.
4. Connect by USB, accept the prompt, choose the device in Android Studio, press **Run ▶**.
   Or from a terminal: `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
5. No cable? Build the APK, copy `app-debug.apk` to the phone, open it, and allow "Install unknown apps" for your file manager.

---

## 4. First-run permission checklist

The Home screen shows a red **Setup needed** card until the required items are done; Settings shows the full list.

- [ ] **Notifications** — tap *Allow* (Android 13+ asks at runtime). Required: dismiss buttons and the reminder live in notifications.
- [ ] **Alarms & reminders** — only needs action on Android 12/12L; shows ✓ automatically on 13+.
- [ ] **Full-screen alarm on lock screen** — Android 14+: *Open* → allow. Without it the alarm still sounds, but appears as a heads-up notification instead of a full screen.
- [ ] **Battery: unrestricted** — recommended on Samsung/Xiaomi/OnePlus etc. In the list that opens, find Sleep Buttons → *Unrestricted/Don't optimize*. Also exclude it from any "sleeping apps" list.
- [ ] Alarm volume above zero (Home warns if it's muted).
- [ ] Enter and confirm your melatonin dose in **Settings** (the reminder button sends you there until you do).

---

## 5. Unit tests (all passing in the sandbox)

| Requirement | Test class |
|---|---|
| Nap offsets & validation | `NapLogicTest` |
| Distinct alarm identities | `AlarmSlotTest` |
| Duplicate-button behaviour | `NapStartPolicyTest` |
| Next-occurrence scheduling (incl. DST gap) | `ReminderLogicTest` |
| Cross-midnight dose-log checks | `DoseWindowTest` |
| µg/mg conversion | `DoseTest` |
| Sleep intervals across midnight (incl. DST fall-back) | `SleepSummaryTest` |
| Clock-time summaries near midnight | `SleepSummaryTest` |
| Stale alarms, elapsed-time preservation | `AlarmTimingTest` |

The Android layer (controller, receivers, service) has no automated tests — use the device checklist.

---

## 6. Physical-device test checklist

For quick testing, set nap offsets to **1 / 2 minutes** in Settings, then restore 35 / 40.

- [ ] **Screen off:** start nap, lock the phone. Alarm 1 turns the screen on (full-screen) or shows a heads-up, sounds and vibrates.
- [ ] **Dismiss alarm 1, alarm 2 still fires:** dismiss "Nap: get up"; Home still shows "Nap: backup — set"; it rings a minute later.
- [ ] **Cancel get-up only:** start a nap, tap the small *Cancel* next to "Nap: get up" → only the backup rings.
- [ ] **Timings persist:** change both timings with − / +, swipe the app away, reopen → same values.
- [ ] **End nap:** start a nap, tap *End nap / cancel remaining alarm* → neither alarm rings. Also try "End nap (cancel backup)" from the ringing notification.
- [ ] **Double tap / duplicate:** tap Start nap twice fast → one session. Tap again later → "already active" dialog with *Replace*.
- [ ] **Removed from recents:** start a nap, swipe the app away → alarms still ring.
- [ ] **Battery saver on:** repeat the screen-off test.
- [ ] **Permission denied/revoked:** deny notifications (or on Android 12, turn off Alarms & reminders) → buttons give "Not scheduled: …", never "set". Re-enable → reopen app → works.
- [ ] **Reboot:** schedule a melatonin reminder for a few minutes out (temporarily change the time in Settings), reboot, unlock → reminder still fires. Start a nap, reboot past the alarm time → it is **not** sounded late; a "missed" notice appears.
- [ ] **Time-zone change:** with a reminder scheduled, turn off automatic time zone and pick another zone → Home shows the same local time (new date/time label). An active nap keeps its remaining minutes.
- [ ] **Manual clock change:** start a nap, move the clock forward 1 hour → the alarm still rings after the original number of minutes.
- [ ] **Melatonin:** reminder text reads exactly as specified; *Taken* adds one dose log; *Skip* adds none; a second tap on Set reminder while one is pending shows the existing one; after logging a dose tonight, Set reminder shows the duplicate-dose warning.
- [ ] **Force-stop limitation (expected failure):** Settings → Apps → Sleep Buttons → *Force stop* → scheduled alarms do **not** ring until you open the app again. This is Android behaviour, not a bug — don't force-stop it.
- [ ] **DND / muted alarm volume:** check behaviour with your own DND rules; alarms follow the alarm stream.

---

## 7. Android-version notes

- **Android 12 (31–32):** exact alarms need the user-granted "Alarms & reminders" special access; revoking it cancels the app's alarms (the app shows "Not scheduled" on next open; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` triggers a re-schedule when re-granted).
- **Android 13 (33):** runtime `POST_NOTIFICATIONS`. `USE_EXACT_ALARM` becomes available (granted at install).
- **Android 14 (34):** `SCHEDULE_EXACT_ALARM` is denied by default for new installs (not used on 13+ here). Full-screen intents are limited to calling/alarm apps and the user can turn them off — hence the optional setup step and `canUseFullScreenIntent()` check. Foreground services must declare a type: the ringing service uses `systemExempted` (documented for apps holding exact-alarm permission that use an FGS to continue an alarm), falling back to `mediaPlayback`, and if neither is allowed, to an insistent notification on an alarm-sound channel.
- **Android 15 (35):** stricter background foreground-service starts; this app starts its service only from an exact-alarm delivery, which is an allowed case, and never from `BOOT_COMPLETED`.
- **OEMs:** aggressive battery managers can still delay or kill alarms. See dontkillmyapp.com for your phone model.

I couldn't open the developer.android.com pages from the build environment to re-verify these details word-for-word; they reflect the official guidance as of mid-2026. Worth a skim:
- Schedule exact alarms (Android 14 change): https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Scheduling alarms guide: https://developer.android.com/training/scheduling/alarms
- Foreground service types required (Android 14): https://developer.android.com/about/versions/14/changes/fgs-types-required
- Full-screen intent limits: https://source.android.com/docs/core/permissions/fsi-limits

---

## Scope notes

Not included, by design: Google Clock integration (the `AlarmScheduler` interface is the seam for it), wearables, Health Connect, automatic sleep detection, snooze, recurring reminders, dose optimisation, light-therapy, or any medical advice. Nothing here diagnoses anything; the summaries only describe what you logged.
