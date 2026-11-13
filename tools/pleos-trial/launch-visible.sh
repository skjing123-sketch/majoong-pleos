#!/bin/bash
set -euo pipefail
umask 077
trial_here="$(cd "$(dirname "$0")" && pwd -P)"
# Validate explicit operator configuration before querying or starting any runtime.
/usr/bin/perl -I"$trial_here" -MTrialConfig -e '1'
trial_base="$MAJOONG_TRIAL_BASE"
trial_mount="$MAJOONG_TRIAL_MOUNT"
trial_user="$MAJOONG_TARGET_USER"
trial_adb_port="${MAJOONG_ADB_PORT:-15037}"
trial_emulator_port="${MAJOONG_EMULATOR_PORT:-5568}"
trial_emulator="$trial_base/sdk/emulator/emulator"
trial_avd="$MAJOONG_AVD_NAME"
trial_die() { printf 'STOP: %s\n' "$*" >&2; exit 1; }
trial_validate() {
  [ "$(/usr/bin/id -un)" = "$trial_user" ] || trial_die 'Expected configured trial account.'
  [ "$(/usr/bin/uname -m)" = arm64 ] || trial_die 'Expected ARM64.'
  [ "$(/usr/bin/stat -f '%Su' /dev/console)" = "$trial_user" ] || trial_die 'Configured trial GUI session is not active.'
  [ "$(/usr/sbin/diskutil info -plist "$trial_mount" | /usr/bin/plutil -extract MountPoint raw -o - -)" = "$trial_mount" ] || trial_die 'External mount absent.'
  [ "$(/usr/sbin/diskutil info -plist "$trial_mount" | /usr/bin/plutil -extract Internal raw -o - -)" = false ] || trial_die 'Expected external disk.'
  [ "$(/usr/sbin/diskutil info -plist "$trial_mount" | /usr/bin/plutil -extract FilesystemType raw -o - -)" = apfs ] || trial_die 'Expected APFS.'
  [ ! -L "$trial_base" ] || trial_die 'Trial root may not be symlinked.'
  [ "$(/usr/sbin/sysctl -n kern.memorystatus_vm_pressure_level)" = 1 ] || trial_die 'Target pressure must be1.'
  trial_internal=$(/bin/df -k /System/Volumes/Data | /usr/bin/awk 'NR==2{print $4}')
  trial_external=$(/bin/df -k "$trial_mount" | /usr/bin/awk 'NR==2{print $4}')
  [ "$trial_internal" -ge 8388608 ] || trial_die 'Internal floor below8GiB.'
  [ "$trial_external" -ge 1048576 ] || trial_die 'External floor below1GiB.'
  trial_battery=$(/usr/bin/pmset -g batt | /usr/bin/awk 'match($0,/[0-9]+%/){v=substr($0,RSTART,RLENGTH-1); print v; exit}')
  case "$trial_battery" in ''|*[!0-9]*) trial_die 'Battery unavailable.';; esac
  [ "$trial_battery" -ge 30 ] && [ "$trial_battery" -le 100 ] || trial_die 'Battery below30percent or invalid.'
  [ -x "$trial_emulator" ] && [ -f "$trial_base/avd/$trial_avd.ini" ] || trial_die 'Preserved SDK/AVD missing.'
  /usr/bin/awk -F' = ' '$1=="hw.ramSize"&&$2=="4096"{r=1}$1=="hw.cpu.ncore"&&$2=="4"{c=1}$1=="hw.gpu.mode"&&$2=="host"{g=1}END{exit !(r&&c&&g)}' "$trial_base/avd/$trial_avd.avd/config.ini" || trial_die 'Preserved4GiB/4core/hostGPU settings differ.'
  if /bin/ps -axo command= | /usr/bin/awk -v avd="$trial_avd" -v port="$trial_emulator_port" '$1 ~ /qemu-system-aarch64/ && index($0,"-avd " avd) && index($0,"-port " port) {found=1}END{exit !found}'; then trial_die 'Owned AVD is already active; inspect its current identity.'; fi
  printf 'VALIDATED ONLY: externalAPFS pressure1 internalKiB=%s externalKiB=%s batteryPct=%s configuredGUI=verified. No binary/ADB/QEMU started.\n' "$trial_internal" "$trial_external" "$trial_battery"
}
case "${1:---plan}" in
 --plan)
  printf 'PLAN ONLY: visible native emulator; no runtime commands executed.\n'
  if [ -f "$trial_base/launch-command.txt" ]; then printf 'Original: '; /bin/cat "$trial_base/launch-command.txt"; fi
  printf 'Change: remove -no-window only; -memory4096/-cores4 make existing config explicit.\n'
  exit 0;;
 --validate-only) trial_validate; exit 0;;
 --start-visible)
  [ "${2:-}" = --build-complete ] || trial_die 'Root must confirm isolated build is finished.'
  trial_validate
  # Blocks only active Java tooling under this trial root; never stops any job.
  if /bin/ps -axo command= | /usr/bin/awk -v base="$trial_base/" 'index($1,base)==1 && $1 ~ /\/bin\/(java|javac|keytool)$/ {busy=1}END{exit !busy}'; then trial_die 'Trial Java/build task still active; finish it before emulator.'; fi
  trial_tag=$(/bin/date -u '+%Y%m%dT%H%M%SZ')
  trial_out="$trial_base/verification/visible-$trial_tag-$$"
  [ ! -e "$trial_out" ] || trial_die 'Fresh output path required.'
  /bin/mkdir -p "$trial_out"
  printf '%s\n' "$$" > "$trial_out/launch-shell.pid"
  /usr/bin/id -u > "$trial_out/launch-uid.txt"
  /bin/ps -p "$$" -o lstart= > "$trial_out/launch-shell-start.txt"
  if [ -f "$trial_base/launch-command.txt" ]; then /bin/cat "$trial_base/launch-command.txt" > "$trial_out/original-launch-command.txt"; fi
  printf '%s\n' "-avd $trial_avd -port $trial_emulator_port -no-snapshot -gpu host -feature -Vulkan -no-passive-gps -no-audio -memory 4096 -cores 4" > "$trial_out/visible-launch-command.txt"
  printf '%s\n' 'Only intended UI change: -no-window removed. RAM/cores explicitly match existing config. Source guard unchanged. No wipe/cold userdata reset. No global env/power settings.' > "$trial_out/change-record.txt"
  printf 'EXECUTING OWN VISIBLE EMULATOR: output=%s shellPID=%s. Confirm actual QEMU identity separately; this shell PID is not yet proof.\n' "$trial_out" "$$"
  exec /usr/bin/env ANDROID_HOME="$trial_base/sdk" ANDROID_SDK_ROOT="$trial_base/sdk" ANDROID_AVD_HOME="$trial_base/avd" ANDROID_USER_HOME="$trial_base/android-home" ANDROID_EMULATOR_HOME="$trial_base/android-home" ANDROID_ADB_SERVER_PORT="$trial_adb_port" TMPDIR="$trial_base/tmp" "$trial_emulator" -avd "$trial_avd" -port "$trial_emulator_port" -no-snapshot -gpu host -feature -Vulkan -no-passive-gps -no-audio -memory 4096 -cores 4 > "$trial_out/emulator.log" 2>&1
  ;;
 *) trial_die 'Use --plan, --validate-only, or --start-visible --build-complete.';;
esac
