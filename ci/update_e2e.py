#!/usr/bin/env python3
"""End-to-end test of the in-app updater on an emulator.

Installs an older published release, then lets the app find, download and install the newest
release from GitHub by itself:
  A. automatic: open the app, leave it, the update installs in the background
  B. manual: Settings > Update now
  C. without "install unknown apps" allowed: the app must hand over to the system prompt

    python3 ci/update_e2e.py --old-apk old.apk --old-code 5 --new-code 6 --out out
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


def ui_nodes():
    for _ in range(5):
        out = sh("uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml")
        if out.strip().startswith("<?xml"):
            try:
                return list(ET.fromstring(out[out.index("<hierarchy"):]).iter("node"))
            except (ET.ParseError, ValueError):
                pass
        time.sleep(1)
    return []


def find_text(pattern, timeout=30):
    rx = re.compile(pattern, re.I)
    end = time.time() + timeout
    while time.time() < end:
        for n in ui_nodes():
            label = n.get("text") or n.get("content-desc") or ""
            if rx.search(label):
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


def scenario_auto(args):
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
    notif = "updated to" in sh("dumpsys notification --noredact")
    sh("cmd statusbar expand-notifications")
    time.sleep(2)
    shot("A2-updated-notification")
    sh("cmd statusbar collapse")
    record("A automatic update", ok and notif,
           f"version now {version_code()}, 'updated' notification {'shown' if notif else 'missing'}")


def scenario_manual(args):
    install_old(args.old_apk, args.old_code, allow_installs=True)
    sh(f"am start -W -n {PKG}/.MainActivity --es open settings")
    btn = find_text(r"^update now$", timeout=10)
    if btn is None:
        check = find_text(r"^check for updates$", timeout=30)
        if check is None:
            shot("B1-settings")
            record("B manual update", False, "Settings has no 'Check for updates' button")
            return
        tap(check)
        btn = find_text(r"^update now$", timeout=60)
    shot("B1-update-now")
    if btn is None:
        record("B manual update", False, "no 'Update now' after checking")
        return
    tap(btn)
    ok = wait_for_version(args.new_code, timeout=300)
    record("B manual update", ok, f"version now {version_code()}")


def scenario_no_permission(args):
    install_old(args.old_apk, args.old_code, allow_installs=False)
    sh(f"am start -W -n {PKG}/.MainActivity --es open settings")
    btn = find_text(r"^update now$", timeout=10)
    if btn is None:
        check = find_text(r"^check for updates$", timeout=30)
        if check:
            tap(check)
        btn = find_text(r"^update now$", timeout=60)
    if btn is None:
        record("C needs permission", False, "no 'Update now'")
        return
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
           f"system prompt {'shown' if shown else 'NOT shown'} ({f[:160]}), version {version_code()}")
    sh("input keyevent KEYCODE_BACK")


def main():
    global OUT
    p = argparse.ArgumentParser()
    p.add_argument("--old-apk", required=True)
    p.add_argument("--old-code", type=int, required=True)
    p.add_argument("--new-code", type=int, required=True)
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
        for scenario in (scenario_auto, scenario_manual, scenario_no_permission):
            try:
                scenario(args)
            except Exception as e:  # keep going: one broken scenario must not hide the others
                record(scenario.__name__, False, f"crashed: {e}")
    finally:
        logcat.terminate()
    subprocess.run(f"grep -E 'Updater|InstallResult|UpdateWorker|PackageInstaller|AndroidRuntime' {OUT}/logcat.txt > {OUT}/logcat-app.txt",
                   shell=True)

    lines = [f"## Update test on API {api}: {args.old_code} → {args.new_code}", ""]
    lines += [f"- {'✅' if ok else '❌'} {name}: {detail}" for name, ok, detail in RESULTS]
    open(f"{OUT}/summary.md", "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    sys.exit(0 if RESULTS and all(ok for _, ok, _ in RESULTS) else 1)


if __name__ == "__main__":
    main()
