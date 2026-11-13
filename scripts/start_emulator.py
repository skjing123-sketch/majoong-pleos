#!/usr/bin/env python3
"""Start this project's emulator independently of the launcher terminal."""
import os
import argparse
import platform
import re
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--avd', default='Majoong_Pleos')
parser.add_argument('--port', type=int, default=5560)
parser.add_argument('--sdk-root', type=Path,
                    default=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
                                 or str(Path.home() / '.cache/majoong-dev/android-sdk')))
parser.add_argument('--avd-home', type=Path,
                    default=Path(os.environ.get('ANDROID_AVD_HOME')
                                 or str(Path.home() / '.cache/majoong-dev/avd')))
parser.add_argument('--manual-gnss', action='store_true',
                    help='Disable passive GPS updates for checksummed manual NMEA tests. Requires a GNSS feeder.')
parser.add_argument('--no-audio', action='store_true',
                    help='Diagnostic mode: disable emulator audio input/output to isolate CoreAudio hangs.')
parser.add_argument('--framebuffer',
                    help='Diagnostic framebuffer WIDTHxHEIGHT, e.g. 1280x720; leaves the AVD profile unchanged.')
args = parser.parse_args()
if args.port < 5554 or args.port > 5682 or args.port % 2:
    parser.error('--port must be an even emulator console port between 5554 and 5682')
if args.framebuffer and not re.fullmatch(r'[1-9][0-9]{2,3}x[1-9][0-9]{2,3}', args.framebuffer):
    parser.error('--framebuffer must be WIDTHxHEIGHT with each dimension between 100 and 9999')
runtime = root / '.run'
runtime.mkdir(exist_ok=True)
env = dict(os.environ, ANDROID_SDK_ROOT=str(args.sdk_root),
           ANDROID_AVD_HOME=str(args.avd_home))
command = [str(args.sdk_root / 'emulator/emulator'), '-avd', args.avd,
           '-port', str(args.port), '-no-snapshot', '-gpu', 'host']
# Android's documented Apple Silicon workaround preserves GLES acceleration.
# Disabling Activity hardware acceleration does not disable WebView's GPU process.
if platform.system() == 'Darwin' and platform.machine() in ('arm64', 'aarch64'):
    command += ['-feature', '-Vulkan']
if args.manual_gnss:
    # Official QEMU2 flag stops PassiveGpsUpdater; geo fix would still emit an
    # immediate generated NMEA pair, so use feed_gnss/drive_gnss_route without --sync-auto.
    command += ['-no-passive-gps']
if args.no_audio:
    command += ['-no-audio']
if args.framebuffer:
    # The emulator's documented numeric -skin overrides only this launch.
    command += ['-skin', args.framebuffer]
with (runtime / 'emulator.log').open('ab') as log:
    process = subprocess.Popen(
        command,
        env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
(runtime / 'emulator.pid').write_text(str(process.pid) + '\n')
print('Pleos 에뮬레이터를 독립 실행했습니다. PID:', process.pid)
