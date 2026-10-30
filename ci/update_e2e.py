#!/usr/bin/env python3
"""End-to-end test of the in-app updater on an emulator.

Installs an older published release, then lets the app find, download and install the newest
release from GitHub by itself:
  A. automatic: open the app, leave it, the update installs in the background
  B. manual: Settings > Update now
  C. without "install unknown apps" allowed: the app must hand over to the system prompt
Then, with --new-apk, checks the Home screen widget on the release build (which, unlike the debug
build of the live tests, is shrunk by R8):
  D. add the widget from Settings, add a to-do with its +, tick the to-do off on the Home screen
  E. connect a Google Sheet (sheet-sync/sample.json stands in for the sheet's web app): its lines
     come in, marked "Google Sheet" in the app and on the widget

    python3 ci/update_e2e.py --old-apk old.apk --old-code 5 --new-code 6 [--new-apk new.apk] --out out
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.ljdstechva.todo"
OUT = "out"
RESULTS = []
# Android lets an installer update the same app without asking only once per 30 s (its
# SilentUpdatePolicy); sooner, it shows its own install screen. Real updates are hours apart.
SILENT_UPDATE_GAP = 35
last_silent_update = 0.0


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stdout}{r.stderr}")
    return r.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def version_code():
    m = re.search(r"versionCode=(\d+)", sh(f"dumpsys package {PKG}"))
    return int(m.group(1)) if m else None


def shot(name):
    sh("screencap -p /data/local/tmp/u.png")
    adb("pull", "/data/local/tmp/u.png", f"{OUT}/{name}.png")


# What the last failed UI dump said, for the report.
dump_error = ""


def ui_nodes():
    global dump_error
    for _ in range(5):
        # Remove the last dump first: a failed dump would otherwise read the previous screen's.
        out = sh("rm -f /data/local/tmp/ui.xml; uiautomator dump /data/local/tmp/ui.xml 2>&1; echo '<<<'; cat /data/local/tmp/ui.xml 2>/dev/null")
        status, _, xml = out.partition("<<<")
        if "<hierarchy" in xml:
            try:
                return list(ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node"))
            except (ET.ParseError, ValueError):
                pass
        if status.strip() != dump_error:
            dump_error = status.strip()
            log(f"UI dump failed: {dump_error[-300:]}")
        time.sleep(1)
    return []


ANR = re.compile(r"isn.t responding", re.I)


def find_text(pattern, timeout=30):
    rx = re.compile(pattern, re.I)
    return find_node(lambda n, label: rx.search(label), timeout)


def find_node(match, timeout=30):
    """First node for which match(node, its text or description) is true."""
    end = time.time() + timeout
    while time.time() < end:
        nodes = ui_nodes()
        labels = [n.get("text") or n.get("content-desc") or "" for n in nodes]
        if any(ANR.search(l) for l in labels):
            # A slow emulator's "Pixel Launcher isn't responding" covers the app (and the UI dump).
            wait = next((n for n, l in zip(nodes, labels) if re.fullmatch(r"wait", l, re.I)), None)
            log(f"Closing a system dialog: {next(l for l in labels if ANR.search(l))}")
            if wait is not None:
                tap(wait)
            else:
                sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
            continue
        for n, label in zip(nodes, labels):
            if match(n, label):
                return n
        time.sleep(1)
    return None


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


def wait_for_version(code, timeout):
    end = time.time() + timeout
    while time.time() < end:
        v = version_code()
        if v == code:
            return True
        time.sleep(3)
    return False


def wait_for_notification(text, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if text in sh("dumpsys notification --noredact"):
            return True
        time.sleep(2)
    return False


def respect_silent_update_gap():
    wait = last_silent_update + SILENT_UPDATE_GAP - time.time()
    if wait > 0:
        log(f"Waiting {wait:.0f} s: Android's minimum gap between silent updates")
        time.sleep(wait)


def focus():
    return " ".join(l.strip() for l in sh("dumpsys window").splitlines() if "mCurrentFocus" in l or "mFocusedApp" in l)


def install_old(apk, old_code, allow_installs):
    sh(f"am force-stop {PKG}")
    adb("uninstall", PKG)
    adb("install", "-g", apk, check=True, timeout=300)
    assert version_code() == old_code, f"old version not installed ({version_code()})"
    sh(f"appops set {PKG} REQUEST_INSTALL_PACKAGES {'allow' if allow_installs else 'default'}")
    sh(f"cmd deviceidle whitelist +{PKG}")


def record(name, ok, detail):
    RESULTS.append((name, ok, detail))
    log(f"{'PASS' if ok else 'FAIL'} {name}: {detail}")


def open_settings():
    """Opens the app on Settings the way an update notification does. Returns a note when the
    link did not land there (versions before 1.0.7 could lose it if Android recreated the screen
    during start-up) and the gear button had to be used instead."""
    sh(f"am start -W -n {PKG}/.MainActivity --es open settings")
    if find_text(r"^(update now|check for updates)$", timeout=15) is not None:
        return ""
    gear = find_text(r"^settings$", timeout=10)
    if gear is None:
        return " (Settings link failed, no gear button)"
    tap(gear)
    return " (Settings link failed; opened with the gear button)"


def scenario_auto(args):
    global last_silent_update
    respect_silent_update_gap()
    install_old(args.old_apk, args.old_code, allow_installs=True)
    sh(f"am start -W -n {PKG}/.MainActivity")
    banner = find_text(r"is available", timeout=60)
    shot("A1-banner")
    if banner is None:
        record("A automatic update", False, "no 'available' banner after opening the app")
        return
    sh("input keyevent KEYCODE_HOME")
    log("Left the app; waiting for the background install")
    ok = wait_for_version(args.new_code, timeout=300)
    if ok:
        last_silent_update = time.time()
    notif = ok and wait_for_notification("updated to")
    sh("cmd statusbar expand-notifications")
    time.sleep(2)
    shot("A2-updated-notification")
    sh("cmd statusbar collapse")
    record("A automatic update", ok and notif,
           f"version now {version_code()}, 'updated' notification {'shown' if notif else 'missing'}")


def scenario_manual(args):
    global last_silent_update
    install_old(args.old_apk, args.old_code, allow_installs=True)
    note = open_settings()
    btn = find_text(r"^update now$", timeout=10)
    if btn is None:
        check = find_text(r"^check for updates$", timeout=30)
        if check is None:
            shot("B1-settings")
            record("B manual update", False, "Settings has no 'Check for updates' button" + note)
            return
        tap(check)
        btn = find_text(r"^update now$", timeout=60)
    shot("B1-update-now")
    if btn is None:
        record("B manual update", False, "no 'Update now' after checking" + note)
        return
    respect_silent_update_gap()
    btn = find_text(r"^update now$", timeout=30) or btn  # again: a system dialog may have covered it meanwhile
    tap(btn)
    ok = wait_for_version(args.new_code, timeout=300)
    if ok:
        last_silent_update = time.time()
    else:
        shot("B2-after-tap")
    record("B manual update", ok, f"version now {version_code()}" + note)


def scenario_no_permission(args):
    install_old(args.old_apk, args.old_code, allow_installs=False)
    note = open_settings()
    btn = find_text(r"^update now$", timeout=10)
    if btn is None:
        check = find_text(r"^check for updates$", timeout=30)
        if check:
            tap(check)
        btn = find_text(r"^update now$", timeout=60)
    if btn is None:
        record("C needs permission", False, "no 'Update now'" + note)
        return
    respect_silent_update_gap()  # so the prompt comes from the missing permission, not the gap
    btn = find_text(r"^update now$", timeout=30) or btn
    tap(btn)
    end, f = time.time() + 120, ""
    while time.time() < end:
        f = focus()
        if re.search(r"packageinstaller|permissioncontroller|com\.android\.settings", f, re.I):
            break
        time.sleep(2)
    time.sleep(2)
    shot("C1-system-prompt")
    shown = bool(re.search(r"packageinstaller|permissioncontroller|com\.android\.settings", f, re.I))
    still_old = version_code() == args.old_code
    record("C needs permission", shown and still_old,
           f"system prompt {'shown' if shown else 'NOT shown'} ({f[:160]}), version {version_code()}" + note)
    sh("input keyevent KEYCODE_BACK")


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def wait_gone(pattern, timeout=10):
    """True once no node's text matches pattern."""
    rx = re.compile(pattern, re.I)
    end = time.time() + timeout
    while time.time() < end:
        if not any(rx.search(n.get("text") or n.get("content-desc") or "") for n in ui_nodes()):
            return True
        time.sleep(1)
    return False


def scroll_to_text(pattern, tries=8):
    w, h = screen_size()
    for _ in range(tries):
        n = find_text(pattern, timeout=2)
        if n is not None:
            return n
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
    return None


def find_on_home(match):
    """The launcher puts a new widget on the first Home screen page with room for it."""
    n = find_node(match, timeout=5)
    if n is not None:
        return n
    w, h = screen_size()
    sh("input keyevent KEYCODE_HOME")
    sh("input keyevent KEYCODE_HOME")  # the second press goes to the first page
    for _ in range(4):
        n = find_node(match, timeout=3)
        if n is not None:
            return n
        sh(f"input swipe {w * 9 // 10} {h // 2} {w // 10} {h // 2} 300")
    return None


def widget_checkbox(title):
    """The widget's checkbox for a to-do: it carries the title as its description."""
    row = find_on_home(lambda n, _: (n.get("content-desc") or "").lower() == title.lower())
    if row is None:
        return None
    return next((c for c in row.iter("node") if c.get("checkable") == "true"), row)


def scenario_widget(args):
    title = "Widget check"
    sh(f"am force-stop {PKG}")
    adb("uninstall", PKG)
    adb("install", "-g", args.new_apk, check=True, timeout=300)
    sh(f"cmd deviceidle whitelist +{PKG}")

    def fail(name, why):
        shot(name)
        labels = sorted({n.get("text") or n.get("content-desc") or "" for n in ui_nodes()} - {""})
        record("D widget on the release build", False, f"{why}; screen shows {labels[:15]}")

    # The launcher once took the "Add to home screen" tap and placed nothing (API 34, run 23), so
    # a missed tap is tapped again and a missing widget is asked for once more, said in the result.
    confirm_rx = r"^(add to home ?screen|add automatically|add)$"
    plus, retried = None, ""
    for attempt in (1, 2):
        open_settings()
        add_widget = scroll_to_text(r"^add the widget$")
        if add_widget is None:
            return fail("D1-settings", "Settings has no 'Add the widget'")
        tap(add_widget)
        confirm = find_text(confirm_rx, timeout=15)
        if confirm is None:
            return fail("D1-pin-dialog", "the launcher did not offer to add the widget")
        shot("D1-pin-dialog")
        tap(confirm)
        if not wait_gone(confirm_rx, timeout=8):
            log("The add-widget sheet is still open; tapping Add again")
            confirm = find_text(confirm_rx, timeout=2)
            if confirm is not None:
                tap(confirm)
        time.sleep(3)

        # The widget's + opens the app on a new to-do. (uiautomator dumps show no label for the
        # app's own "New to-do" button, so the to-do is made from the widget.)
        sh("input keyevent KEYCODE_HOME")
        plus = find_on_home(lambda n, label: label.lower() == "new to-do")
        if plus is not None:
            break
        if attempt == 1:
            shot("D2-home-first-try")
            log("No widget on the Home screen after adding it; asking once more")
            retried = " (the widget was added on the second request)"
    if plus is None:
        return fail("D2-home", "the widget's + is not on the Home screen, after two requests")
    tap(plus)
    time.sleep(3)  # the app opens and the title field takes the focus
    sh("input text " + title.replace(" ", "%s"))
    save = find_text(r"^save$", timeout=10)
    if save is None:
        return fail("D2-new-todo", "no Save button")
    tap(save)
    if find_text(rf"^{title}$", timeout=10) is None:
        return fail("D2-new-todo", "could not add a to-do")

    sh("input keyevent KEYCODE_HOME")
    box = widget_checkbox(title)
    if box is None:
        shot("D3-home")
        record("D widget on the release build", False, "widget not found on the Home screen")
        return
    was = box.get("checked")
    tap(box)
    end, ticked = time.time() + 20, False
    while time.time() < end and not ticked:
        time.sleep(2)
        box = widget_checkbox(title)
        ticked = box is not None and box.get("checked") == "true"
    shot("D3-widget-ticked")
    sh(f"am start -W -n {PKG}/.MainActivity")
    in_app = find_text(r"^completed · 1$", timeout=15) is not None
    shot("D4-app")
    seen = "" if in_app else f" (screen shows {sorted({n.get('text') or n.get('content-desc') or '' for n in ui_nodes()} - {''})[:15]})"
    record("D widget on the release build", was == "false" and ticked and in_app,
           f"checkbox {was} -> {'checked' if ticked else 'NOT checked'}, app shows it "
           f"{'done' if in_app else 'NOT done'}{seen}{retried}")


SAMPLE_SHEET = "https://raw.githubusercontent.com/ljdstechva/ToDo/main/sheet-sync/sample.json"


def labels_now():
    return sorted({n.get("text") or n.get("content-desc") or "" for n in ui_nodes()} - {""})


def scenario_sheet(args):
    """Runs after D, on the same install (the widget is on the Home screen)."""
    name = "E Google Sheet on the release build"

    def fail(shot_name, why):
        shot(shot_name)
        record(name, False, f"{why}; screen shows {labels_now()[:15]}")

    open_settings()
    connect = scroll_to_text(r"^connect a google sheet$")
    if connect is None:
        return fail("E1-settings", "Settings has no 'Connect a Google Sheet'")
    tap(connect)
    # The emulator's keyboard once dropped the link's last characters (API 36, run 24), so the
    # field is read back and typed again until it holds the whole link.
    def is_field(n, _):
        return n.get("class") == "android.widget.EditText"
    typed = None
    for _ in range(3):
        field = find_node(is_field, timeout=10)
        if field is None:
            return fail("E1-dialog", "the connect dialog has no link field")
        tap(field)
        time.sleep(1)
        sh("input keyevent KEYCODE_MOVE_END")
        sh("input keyevent " + " ".join(["KEYCODE_DEL"] * (len(args.sheet_url) + 20)))
        sh(f"input text '{args.sheet_url}'")
        time.sleep(2)
        field = find_node(is_field, timeout=5)
        typed = field.get("text") if field is not None else None
        if typed == args.sheet_url:
            break
        log(f"The link field reads {typed!r}; typing it again")
    else:
        return fail("E1-dialog", f"could not type the link (the field reads {typed!r})")
    button = find_text(r"^connect$", timeout=10)
    if button is None:
        return fail("E1-dialog", "no Connect button")
    shot("E1-dialog")
    tap(button)
    # Connected closes the dialog; the card's Sync now and status can be below the fold.
    if not wait_gone(r"^connect$", timeout=30):
        return fail("E2-connect", "did not connect (the dialog stayed open)")
    if scroll_to_text(r"^sync now$") is None:
        return fail("E2-connect", "did not connect (no Sync now)")
    status = find_text(r"lines? on the sheet", timeout=15)
    shot("E2-connected")

    sh("input keyevent KEYCODE_BACK")
    row = find_text(r"sample client", timeout=15)
    badge = find_text(r"google sheet", timeout=5)
    shot("E3-app")
    sh("input keyevent KEYCODE_HOME")
    on_widget = find_on_home(lambda n, label: "sample client" in label.lower()) is not None
    widget_badge = find_text(r"^google sheet$", timeout=5) is not None
    shot("E4-widget")
    record(name, row is not None and badge is not None and on_widget and widget_badge,
           f"status {status.get('text') if status is not None else 'NOT shown'}; app: line "
           f"{'shown' if row is not None else 'NOT shown'}, badge {'shown' if badge is not None else 'NOT shown'}; "
           f"widget: line {'shown' if on_widget else 'NOT shown'}, badge {'shown' if widget_badge else 'NOT shown'}")


def main():
    global OUT
    p = argparse.ArgumentParser()
    p.add_argument("--old-apk", required=True)
    p.add_argument("--old-code", type=int, required=True)
    p.add_argument("--new-code", type=int, required=True)
    p.add_argument("--new-apk", help="the new release, for the widget and Google Sheet checks")
    p.add_argument("--sheet-url", default=SAMPLE_SHEET, help="a web app answer for the Google Sheet check")
    p.add_argument("--out", default="out")
    args = p.parse_args()
    OUT = args.out
    subprocess.run(["mkdir", "-p", OUT])

    api = sh("getprop ro.build.version.sdk").strip()
    log(f"API {api}: update {args.old_code} -> {args.new_code}")
    # "Booted" comes before the user is unlocked; installs and alarms in that window misbehave.
    for _ in range(90):
        if "RUNNING_UNLOCKED" in sh("dumpsys user"):
            break
        time.sleep(2)
    time.sleep(30)
    for cmd in ("settings put global window_animation_scale 0", "settings put global transition_animation_scale 0",
                "settings put global animator_duration_scale 0", "svc power stayon true",
                "input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard"):
        sh(cmd)
    adb("logcat", "-c")
    logcat = subprocess.Popen(["adb", "logcat", "-v", "time"], stdout=open(f"{OUT}/logcat.txt", "w"), stderr=subprocess.STDOUT)
    try:
        scenarios = [scenario_auto, scenario_manual, scenario_no_permission]
        if args.new_apk:
            scenarios += [scenario_widget, scenario_sheet]
        for scenario in scenarios:
            try:
                scenario(args)
            except Exception as e:  # keep going: one broken scenario must not hide the others
                record(scenario.__name__, False, f"crashed: {e}")
    finally:
        logcat.terminate()
    subprocess.run(f"grep -E 'Updater|InstallResult|UpdateWorker|SheetSync|PackageInstaller|AndroidRuntime' {OUT}/logcat.txt > {OUT}/logcat-app.txt",
                   shell=True)

    lines = [f"## Update test on API {api}: {args.old_code} → {args.new_code}", ""]
    lines += [f"- {'✅' if ok else '❌'} {name}: {detail}" for name, ok, detail in RESULTS]
    open(f"{OUT}/summary.md", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    sys.exit(0 if RESULTS and all(ok for _, ok, _ in RESULTS) else 1)


if __name__ == "__main__":
    main()
