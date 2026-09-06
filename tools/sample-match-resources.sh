#!/system/bin/sh
# Read-only process sampling; stdin is supplied by the host (no device script install).
# schedstat fields: on-CPU nanoseconds, runnable-wait nanoseconds, scheduling count.
app_pid="$1"
duration_seconds="$2"
case "$app_pid:$duration_seconds" in *[!0-9:]*|:*) exit 2;; esac
end_time=$(( $(date +%s) + duration_seconds ))
tick=0
while [ -r "/proc/$app_pid/stat" ]; do
    # Android denies app UIDs /proc/uptime on this device; wall time aligns with logcat.
    wall_time=$(date +%s.%N)
    [ "${wall_time%%.*}" -ge "$end_time" ] && break
    printf 'SAMPLE %s %s\n' "$tick" "$wall_time"
    for task in /proc/"$app_pid"/task/*; do
        [ -r "$task/comm" ] || continue
        read -r name < "$task/comm"
        case "$name" in
            DefaultDispatch*|HeapTaskDaemon|Jit\ thread*|agridmaster.app|Signal\ Catcher)
                read -r cpu wait slices < "$task/schedstat"
                read -r stat < "$task/stat"
                printf 'THREAD %s %s CPU_NS=%s WAIT_NS=%s SLICES=%s\n' "${task##*/}" "$name" "$cpu" "$wait" "$slices"
                printf 'STAT %s\n' "$stat"
                while IFS= read -r line; do
                    case "$line" in Cpus_allowed_list:*|State:*) printf '%s\n' "$line";; esac
                done < "$task/status"
                while IFS= read -r line; do printf 'CGROUP %s\n' "$line"; done < "$task/cgroup"
                ;;
        esac
    done
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        for field in scaling_cur_freq scaling_max_freq; do
            if [ -r "$policy/$field" ]; then
                read -r value < "$policy/$field"
                printf 'FREQ %s %s %s\n' "${policy##*/}" "$field" "$value"
            fi
        done
    done
    if [ $((tick % 4)) -eq 0 ]; then
        while IFS= read -r line; do
            case "$line" in VmRSS:*|VmSwap:*|Threads:*) printf 'MEM %s\n' "$line";; esac
        done < "/proc/$app_pid/status"
        while IFS= read -r line; do
            case "$line" in MemAvailable:*|SwapFree:*) printf 'SYSTEM_MEM %s\n' "$line";; esac
        done < /proc/meminfo
    fi
    tick=$((tick + 1))
    sleep 0.25
done
printf 'SAMPLING_DONE\n'
