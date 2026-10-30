# To-Do

A small, fast to-do app for Android 8 to Android 16 with reminders that really ring.

- **Checklist** with categories (Personal, Work, Errands, or your own). Tap the circle to
  check a to-do off. Swipe it away to delete it; Undo brings it back.
- **Reminder per to-do**, as a **notification** (with Done and Snooze buttons) or an **alarm**
  (rings full screen, also on the lock screen, until you stop it).
- **Repeat:** once, every 3 hours, every 6 hours, every day, every 2 days, every 3 days,
  every week (pick the weekday), every 15 days, every month (pick the day; 29 to 31 fall back to
  the month's last day).
- **Quiet hours** (default 10 PM to 7 AM): the 3- and 6-hour reminders skip them.
- **Five alarm sounds:** Siren (very loud), Classic beeps, Retro phone, Calm chimes and Gentle
  sunrise (both start soft and get louder), plus the phone's own alarm sound.
- **Test it in 5 seconds** button in the editor, to hear and see a reminder before saving.
- A repeating to-do you checked off comes back when its next reminder is due.
- **Dark mode:** Settings → Appearance → System, Light or Dark.
- **Home screen widget:** tick to-dos off right there (they cross out, as in the app); + adds
  one and tapping a to-do opens it. Settings → Home screen widget → Add the widget, or touch
  and hold the Home screen → Widgets → To-Do.
- **Google Sheet sync:** the to-do list of a Google Sheet comes in under Work, marked
  "Google Sheet", and is ticked off when it's done there (see below).
- **Updates itself** from this repo's GitHub releases (Settings → Updates).

## Install on your phone

1. On the phone, open the newest release:
   <https://github.com/ljdstechva/ToDo/releases/latest>
2. Download `ToDo-v1.0.N.apk` and open it. Android asks to allow installs from your
   browser once; allow it, then tap **Install**.
3. Open To-Do and allow notifications when asked.
4. Open **Settings → Reliable reminders** and tap **Fix** on anything marked red.

## Updates

The app looks for a new release every 6 hours and when you open it.

- **Automatic updates on** (the default): a new version downloads and installs by itself
  when you are not using the app, and a notification says "To-Do updated".
- **Off:** you get a notification and install it from Settings → Updates → Update now.

The very first update asks you once to allow To-Do to install apps. After that, updates install
without asking (Android 12 and newer). Your to-dos and reminders are kept.

## Google Sheet sync

The app can follow the **TO-DO & NOTIFICATIONS** list on the Overview tab of a Google Sheet
(made for the FINANCE sheet):

- a new line on the sheet becomes a to-do under **Work**, marked **Google Sheet** (in the list,
  the editor and the widget). Its note has the amount, date and tab; **Open** in the editor opens
  its row in Google Sheets;
- a line that leaves the sheet (it was settled) is **ticked off** in the app; if it comes back,
  the to-do reopens;
- what you do in the app stays in the app: ticking off, editing or deleting a synced to-do never
  changes the sheet, and a to-do you deleted does not come back while its line is still there.

It syncs about every 15 minutes (Android's shortest background interval), when you open the app,
and from Settings → Google Sheet → Sync now. If the sheet says it has more lines than the app
could read, nothing is ticked off and Settings shows why.

### Set it up once (on a computer)

1. Open the sheet, then **Extensions → Apps Script**.
2. Replace the editor's contents with [`sheet-sync/Code.gs`](sheet-sync/Code.gs) and save.
   Optional: pick `preview` and **Run** to see what the app will get (allow access when asked).
3. **Deploy → New deployment →** the gear → **Web app**. *Execute as:* **Me**.
   *Who has access:* **Anyone**. **Deploy**, allow access, and copy the **Web app URL**
   (it ends in `/exec`).
4. On the phone: To-Do → Settings → **Google Sheet → Connect a Google Sheet**, paste the link,
   **Connect**.

The script only reads. It sends nothing but the to-do lines (where, date, amount, what), but
anyone who has the link can read those, so keep the link private. To stop it: Apps Script →
Deploy → Manage deployments → Archive, or Settings → Google Sheet → Disconnect in the app.
After you change the script, use Deploy → Manage deployments → Edit → New version (the link
stays the same).

`sheet-sync/test.js` checks the script's reading of the sheet (`node sheet-sync/test.js`);
`sheet-sync/sample.json` shows what the web app answers.

## How it is built

- Kotlin, Jetpack Compose, Material 3. Data is a single JSON file in the app's private storage.
- Reminders use `AlarmManager`: alarm style with `setAlarmClock` (shows as the phone's next
  alarm, survives Doze), notification style with exact idle alarms. They are re-armed after a
  reboot, a time or time zone change, and an app update.
- The alarm rings from a foreground service on the alarm volume, with vibration, and stops by
  itself after 5 minutes (leaving a "missed alarm" notification).
- The five tones are made by `tools/make_tones.py` (no third-party audio).
- The widget is Jetpack Glance; it redraws whenever the list changes and just after midnight
  (for its "Today" / "Tomorrow" labels).

## Builds and tests

Everything builds on GitHub Actions (`.github/workflows/build.yml`):

1. unit tests (repeat schedule math, release parsing), debug + release APKs, signature check;
2. **live tests on Android 14 and Android 16 emulators** (`app/src/androidTest`): real
   notifications and alarms fire, the alarm screen appears over the lock screen, Done /
   Snooze / Dismiss work, every tone plays, alarms come back after a reboot, and the
   add → remind → check off → swipe → undo flow through the real screens, the widget on
   the real Home screen (added from Settings, ticked off and on), and the Google Sheet sync
   against a stand-in for the sheet's web app. Screenshots are in
   the `live-test-api34` / `live-test-api36` artifacts;
3. on `main`, a release `v1.0.<run number>` with `ToDo-v1.0.<run number>.apk`.

After each release, `update-test.yml` installs the previous release on the emulators and checks
that it updates itself to the new one, then ticks a to-do off with the widget and connects
`sheet-sync/sample.json` as a Google Sheet on the new release.

Releases are signed with a key kept in the repository secrets (`RELEASE_KEYSTORE_B64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Keep a backup
of that key: without it, phones cannot update and the app has to be reinstalled.
