#!/bin/bash
# Read-only: sample only explicitly selected source helper PIDs. No process is stopped.
set -euo pipefail
trial_csv="${1:-}"
case "$trial_csv" in ''|*[!0-9,]*) printf 'Usage: %s PID[,PID...]\n' "$0" >&2; exit 2;; esac
trial_me=$(/usr/bin/id -u)
IFS=, read -r -a trial_pids <<< "$trial_csv"
[ "${#trial_pids[@]}" -le 12 ] || exit 2
for trial_pid in "${trial_pids[@]}"; do
  [ "$(/bin/ps -p "$trial_pid" -o uid= | /usr/bin/tr -d ' ')" = "$trial_me" ] || { printf 'STOP: PID owner differs or exited: %s\n' "$trial_pid" >&2; exit 1; }
done
printf 'Read-only helper baseline: CPU_TIME is cumulative process CPU; %%CPU is ps average, RSS is KiB. No args or credentials are recorded.\n'
for trial_sample in 1 2 3 4 5; do
  /bin/date -u '+%Y-%m-%dT%H:%M:%SZ'
  /usr/sbin/sysctl -n kern.memorystatus_vm_pressure_level vm.swapusage
  /bin/ps -p "$trial_csv" -o pid=,uid=,rss=,time=,pcpu=,comm= || true
  [ "$trial_sample" -eq 5 ] || /bin/sleep 1
done
