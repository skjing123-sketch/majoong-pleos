#!/bin/bash
# Read-only exact identity check; never starts ADB/emulator or kills a process.
set -euo pipefail
trial_pid="${1:-}"
case "$trial_pid" in ''|*[!0-9]*) printf 'Usage: %s ACTUAL_PID\n' "$0" >&2;exit 2;;esac
trial_here="$(cd "$(dirname "$0")" && pwd -P)"
trial_uid=$(/bin/ps -p "$trial_pid" -o uid= | /usr/bin/awk '{print $1}')
trial_start=$(/bin/ps -p "$trial_pid" -o lstart= | /usr/bin/sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
trial_cmd=$(/bin/ps -ww -p "$trial_pid" -o command=)
/usr/bin/perl -I"$trial_here" -MTrialCore=identity_ok -e 'my($pid,$uid,$start,$cmd)=@ARGV;die "Not the exact owned QEMU identity\n"unless identity_ok({pid=>$pid,uid=>$uid,start=>$start,command=>$cmd},{pid=>$pid,uid=>$<,start=>$start});print "pid=$pid\nuid=$uid\nstart=$start\ncommand=$cmd\n";' "$trial_pid" "$trial_uid" "$trial_start" "$trial_cmd"
