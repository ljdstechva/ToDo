#!/usr/bin/env bash
# Runs the on-device live tests (app/src/androidTest) on the booted emulator and collects the
# screenshots, logcat and a Markdown summary into OUT. Exit code: 0 only if every test passed.
#   bash ci/instrumented.sh <dir with app-debug.apk + app-debug-androidTest.apk> <out dir>
set -uo pipefail
APKS=${1:-apks}
OUT=${2:-out}
PKG=com.ljdstechva.todo
mkdir -p "$OUT/shots"

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Emulator API $API"

# Quiet, awake, unlocked phone.
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

adb install -r -g "$APKS/app-debug.apk" || exit 1
adb install -r "$APKS/app-debug-androidTest.apk" || exit 1
adb shell rm -rf /data/local/tmp/todo-shots

timeout 1800 adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/instrument.txt" 2>&1
echo "am instrument exit $?"

adb pull /data/local/tmp/todo-shots "$OUT/" > /dev/null 2>&1 && mv "$OUT"/todo-shots/* "$OUT/shots/" 2>/dev/null
rmdir "$OUT/todo-shots" 2>/dev/null
adb shell dumpsys alarm > "$OUT/dumpsys-alarm.txt" 2>&1
kill "$LOGCAT" 2>/dev/null
grep -E "LiveTest|AlarmService|ReminderReceiver|ReminderScheduler|TestRunner|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/logcat-app.txt"

python3 - "$OUT" "$API" <<'EOF'
import re, sys
out, api = sys.argv[1], sys.argv[2]
text = open(f"{out}/instrument.txt", errors="replace").read()
# `am instrument -r`: blocks of "INSTRUMENTATION_STATUS: key=value" (values may span lines),
# each closed by "INSTRUMENTATION_STATUS_CODE: n" (1 start, 0 ok, -2 failure, -1 error, -3/-4 skipped).
results, fields, key = [], {}, None
for line in text.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
        fields[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ", 1)[1])
        if code != 1:
            results.append((fields.get("class", "?").split(".")[-1], fields.get("test", "?"), code, fields.get("stack", "")))
        fields, key = {}, None
    elif line.startswith("INSTRUMENTATION_"):
        key = None
    elif key:
        fields[key] += "\n" + line
names = {0: "passed", -1: "ERROR", -2: "FAILED", -3: "skipped", -4: "skipped"}
bad = [r for r in results if r[2] in (-1, -2)]
passed = sum(1 for r in results if r[2] == 0)
lines = [f"## Live tests on API {api}: {passed}/{len(results)} passed", ""]
for cls, test, code, _ in results:
    mark = "✅" if code == 0 else ("❌" if code in (-1, -2) else "⏭")
    lines.append(f"- {mark} {cls}.{test} ({names.get(code, code)})")
for cls, test, _, stack in bad:
    lines += ["", f"### {cls}.{test}", "```", "\n".join(stack.strip().splitlines()[:25]), "```"]
crash = re.search(r"INSTRUMENTATION_RESULT: shortMsg=(.*)", text)
if crash:
    lines += ["", f"**Instrumentation crashed:** {crash.group(1)}"]
if not results:
    lines += ["", "**No test results.** End of the output:", "```", text[-3000:], "```"]
open(f"{out}/summary.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if results and not bad and not crash and "OK (" in text else 1)
EOF
