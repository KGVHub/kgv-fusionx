#!/bin/bash
# Repeatable UI smoothness probe: on the launcher, open/close the app drawer
# and scroll it, then report launcher + SystemUI frame stats.
# usage: jank.sh [label]
A="adb -s 1900567000077"
$A shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME' >/dev/null 2>&1
sleep 2
$A shell 'dumpsys gfxinfo com.android.launcher3 reset >/dev/null'
for i in 1 2 3; do
    $A shell 'input swipe 512 520 512 120 250; sleep 1; input swipe 512 450 512 150 200; sleep 1; input swipe 512 150 512 450 200; sleep 1; input keyevent KEYCODE_HOME; sleep 1.5'
done
echo "== ${1:-run} $(date +%T)"
$A shell 'dumpsys gfxinfo com.android.launcher3' | grep -E 'Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile|Slow UI thread|Slow bitmap|Slow issue draw|Frame deadline missed:|^  GPU' | grep -v gpu
