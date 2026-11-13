#!/usr/bin/env python3
"""Development probe: actual NaviHelper requests plus official emulator GNSS input.

Never injects an arrival callback. Use Virtual Console for vehicle gear changes.
Route/observe use a DUMP-protected debug receiver and never foreground the app.
The user-started MonitoringService must already be running (use connect first).
The release APK does not expose the debug route/observe receiver. A rejected
request is not evidence of SDK initialization or navigation failure.
"""
import argparse
from pathlib import Path
import re
import shlex
import subprocess


def probe_intent(command):
    if command in ('route', 'observe'):
        return ['am', 'broadcast', '--user', 'current', '--include-stopped-packages',
                '-n', 'ai.nearby.reunion/.DebugProbeReceiver',
                '-a', 'ai.nearby.reunion.debug.PROBE', '--es', 'majoongDebugProbe',
                'route_sample' if command == 'route' else 'observe']
    if command == 'connect':
        return ['am', 'start', '-W', '--user', 'current', '--activity-single-top',
                '-n', 'ai.nearby.reunion/.MainActivity', '--es', 'majoongDebugProbe', 'connect',
                '--es', 'webUrl', 'http://10.0.2.2:8787/?platform=android&dataMode=live']
    raise ValueError('Only connect, observe, and route are probe intents.')


def broadcast_accepted(output):
    # A broadcast process exit code alone does not mean a running service accepted it.
    return bool(re.search(r'Broadcast completed:\s*result=1(?:,|\s|$)', output))


def activity_started(output):
    # `am start -W` can exit zero even when Activity launch timed out.
    return bool(re.search(r'^Status:\s*ok\s*$', output, re.MULTILINE))


def main():
    from feed_gnss import default_adb
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['connect', 'observe', 'route', 'position-start', 'position-destination', 'console', 'logs'])
    parser.add_argument('--serial', default='emulator-5560')
    parser.add_argument('--adb', type=Path, default=default_adb())
    args = parser.parse_args()
    adb = [str(args.adb), '-s', args.serial]
    if args.command == 'logs':
        subprocess.run(adb + ['logcat', '-d', '-v', 'threadtime', '-s', 'MajoongProbe:I', '*:S'], check=True, timeout=30)
    elif args.command == 'console':
        subprocess.run(adb + ['forward', 'tcp:8088', 'tcp:8088'], check=True, timeout=10)
        print('Pleos Virtual Console: http://127.0.0.1:8088/')
    elif args.command.startswith('position-'):
        if not args.serial.startswith('emulator-'):
            parser.error('GNSS test input is allowed only for an emulator serial.')
        point = ('127.0250', '37.4830') if args.command == 'position-start' else ('127.03636666', '37.48544722')
        subprocess.run(adb + ['emu', 'geo', 'fix', *point, '20', '10', '0'], check=True, timeout=10)
        print('GNSS input sent. This does not prove navigation arrival.')
    else:
        # adb shell reparses its arguments: quote the URL ampersand for the device shell too.
        result = subprocess.run(adb + ['shell', shlex.join(probe_intent(args.command))],
                                capture_output=True, text=True, timeout=45)
        print(result.stdout, end='')
        if result.stderr:
            print(result.stderr, end='')
        if result.returncode:
            raise SystemExit(result.returncode)
        if args.command == 'connect' and not activity_started(result.stdout):
            raise SystemExit('Activity launch was not confirmed. Check the emulator health before retrying.')
        if args.command in ('route', 'observe'):
            if not broadcast_accepted(result.stdout):
                raise SystemExit('Debug probe rejected or receiver unavailable; check MajoongProbe logs. '
                                 'Connect the debug app first. No Activity or foreground service was started by this probe.')
            print('Request delivered to the running monitoring service without changing the foreground app. '
                  'Confirm the actual SDK callback separately.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit('Probe timed out; delivery and execution are unconfirmed. No automatic retry was made.') from None
