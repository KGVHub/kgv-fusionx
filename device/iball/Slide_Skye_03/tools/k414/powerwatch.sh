#!/bin/bash
# Tracks when the iBall loses power or reboots on its own.
# Every 15 s: adb state, uptime, battery level/voltage/current, load,
# hwspinlock failure count -> samples.log. When the tablet disappears and
# comes back with a lower uptime, it logs the event to events.log, with the
# last sample before the drop, Android's boot reason and the pstore records
# (saved to boot-<time>/).
A="adb -s 1900567000077"
D=/mypool/claude/mainline/a11/powerlog
mkdir -p "$D"
last_up=0 last_state=none last_sample=""
log() { echo "$(date '+%F %T') $*" | tee -a "$D/events.log"; }
log "powerwatch started"
while true; do
    state=$($A get-state 2>/dev/null || echo gone)
    if [ "$state" != "$last_state" ]; then
        log "state $last_state -> $state"
        [ "$state" = gone ] && log "last sample before drop: $last_sample"
    fi
    if [ "$state" = device ] || [ "$state" = recovery ]; then
        s=$(timeout 15 $A shell 'cut -d" " -f1 /proc/uptime
            b=/sys/class/power_supply/battery; f=/sys/class/power_supply/sc27xx-fgu
            echo "cap=$(cat $b/capacity 2>/dev/null) st=$(cat $b/status 2>/dev/null) mV=$(( $(cat $f/voltage_now 2>/dev/null || echo 0) / 1000 )) mA=$(( $(cat $f/current_now 2>/dev/null || echo 0) / 1000 ))"
            echo "load=$(cut -d" " -f1 /proc/loadavg) hwlock_fail=$(dmesg 2>/dev/null | grep -c "lock failed")"' 2>/dev/null | tr -d '\r' | tr '\n' ' ')
        up=${s%%.*}; up=${up:-0}
        if [ "$up" -gt 0 ] && [ "$up" -lt "$last_up" ]; then
            log "REBOOT/POWER-OFF detected: uptime ${last_up}s -> ${up}s; before: $last_sample"
            B=$D/boot-$(date +%H%M%S); mkdir -p "$B"
            $A root >/dev/null 2>&1; sleep 3
            log "  boot reason: $(timeout 10 $A shell 'getprop sys.boot.reason; getprop ro.boot.bootreason; getprop persist.sys.boot.reason.history | head -3' 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
            for f in $(timeout 10 $A shell 'ls /sys/fs/pstore/' 2>/dev/null | tr -d '\r'); do
                timeout 20 $A shell "cat /sys/fs/pstore/$f" > "$B/$f" 2>/dev/null
            done
            log "  pstore saved to $B: $(ls "$B" | tr '\n' ' ')"
            # pstore keeps a panic record until it is overwritten: only report
            # one that wasn't there at the previous reboot.
            h=$(md5sum "$B"/dmesg-ramoops-0 2>/dev/null | cut -c1-32)
            if [ -n "$h" ] && [ "$h" != "$(cat "$D/.last_panic" 2>/dev/null)" ]; then
                echo "$h" > "$D/.last_panic"
                p=$(grep -a -h -m3 -E 'Kernel panic|PC is at|Unable to handle' "$B"/dmesg-ramoops-0 | tr '\n' ' ')
                log "  NEW panic record: $p"
            else
                log "  no new panic record (clean reboot, power loss or hard reset)"
            fi
        fi
        [ "$up" -gt 0 ] && last_up=$up
        last_sample="$(date +%T) up=${up}s $s"
        echo "$last_sample" >> "$D/samples.log"
    fi
    last_state=$state
    sleep 15
done
