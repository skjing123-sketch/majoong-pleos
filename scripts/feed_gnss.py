#!/usr/bin/env python3
"""Send checksummed GNSS data through the official Android emulator console.

This feeds GPGGA/GPRMC into the emulated GNSS modem. It never calls the app,
NaviHelper, or an arrival callback. Console acceptance is not proof of arrival.
The ranchu HAL expects integer HHMMSS, despite NMEA allowing fractional seconds.
Keep the real navigation app in the foreground while feeding positions: Android
delivers NMEA callbacks only to foreground clients with GPS enabled. Running the
Majoong Activity first can make the navigation app's NMEA listener inactive.
The debug receiver's observe command does not change the foreground Activity.

Examples:
  python3 scripts/feed_gnss.py --serial emulator-5562 --point start
  python3 scripts/feed_gnss.py --serial emulator-5562 --latitude 37.48320 \
      --longitude 127.0250 --speed-knots 5 --bearing 0
  python3 scripts/feed_gnss.py --serial emulator-5562 --point destination

Reference: https://developer.android.com/studio/run/emulator-console#geo
"""

import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time


POINTS = {
    "start": (37.4830, 127.0250),
    "destination": (37.48544722, 127.03636666),
}


def default_adb():
    """Use the configured SDK or PATH before the historical local cache."""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        return Path(sdk) / "platform-tools" / "adb"
    executable = shutil.which("adb")
    return Path(executable) if executable else Path.home() / ".cache/majoong-dev/android-sdk/platform-tools/adb"


def checksum_sentence(body):
    checksum = 0
    for byte in body.encode("ascii"):
        checksum ^= byte
    return f"${body}*{checksum:02X}"


def coordinate(value, latitude):
    # Round before splitting so 59.9999999 minutes carries into the next degree.
    total = round(abs(value) * 60 * 1_000_000)
    degrees, minute_units = divmod(total, 60 * 1_000_000)
    degree_width = 2 if latitude else 3
    minutes = minute_units / 1_000_000
    hemisphere = ("N" if value >= 0 else "S") if latitude else ("E" if value >= 0 else "W")
    return f"{degrees:0{degree_width}d}{minutes:09.6f}", hemisphere


def nmea_sentences(latitude, longitude, now, speed_knots=0.0, bearing=90.0, altitude=50.0):
    lat, ns = coordinate(latitude, True)
    lon, ew = coordinate(longitude, False)
    utc = now.astimezone(timezone.utc)
    # Fractional seconds are rejected by ranchu's sscanf-based GNSS parser.
    clock = utc.strftime("%H%M%S")
    date = utc.strftime("%d%m%y")
    gga = f"GPGGA,{clock},{lat},{ns},{lon},{ew},1,08,0.9,{altitude:.1f},M,0.0,M,,"
    rmc = f"GPRMC,{clock},A,{lat},{ns},{lon},{ew},{speed_knots:.3f},{bearing:.1f},{date},0.0,E"
    return checksum_sentence(gga), checksum_sentence(rmc)


class ConsoleInputError(Exception):
    """Only a fixed category; never retain command, position, or console output."""

    def __init__(self, category):
        self.category = category
        super().__init__(category)


def send_console_input(adb, serial, arguments):
    try:
        result = subprocess.run([str(adb), "-s", serial, "emu", "geo", *arguments],
                                capture_output=True, text=True, timeout=5)
    except subprocess.TimeoutExpired:
        raise ConsoleInputError("console_timeout") from None
    except OSError:
        raise ConsoleInputError("console_unavailable") from None
    if result.returncode != 0 or "OK" not in result.stdout:
        raise ConsoleInputError("console_rejected")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default="emulator-5562")
    parser.add_argument("--adb", type=Path, default=default_adb())
    parser.add_argument("--point", choices=POINTS)
    parser.add_argument("--latitude", type=float)
    parser.add_argument("--longitude", type=float)
    parser.add_argument("--count", type=int, default=6)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--speed-knots", type=float, default=0.0)
    parser.add_argument("--bearing", type=float, default=90.0)
    parser.add_argument("--altitude", type=float, default=50.0)
    args = parser.parse_args(argv)
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("GNSS test input is permitted only for an emulator serial.")
    supplied_coordinates = args.latitude is not None or args.longitude is not None
    if args.point and supplied_coordinates:
        parser.error("Choose --point or a latitude/longitude pair, not both.")
    if supplied_coordinates and (args.latitude is None or args.longitude is None):
        parser.error("Both --latitude and --longitude are required.")
    latitude, longitude = (args.latitude, args.longitude) if supplied_coordinates else POINTS[args.point or "start"]
    if not all(math.isfinite(value) for value in [latitude, longitude, args.interval, args.speed_knots, args.bearing, args.altitude]):
        parser.error("All numeric inputs must be finite.")
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        parser.error("Coordinates are outside the latitude/longitude range.")
    if not (1 <= args.count <= 300 and 0.2 <= args.interval <= 10):
        parser.error("Use count 1..300 and interval 0.2..10 seconds.")
    if not (0 <= args.speed_knots <= 100 and 0 <= args.bearing < 360):
        parser.error("Use speed 0..100 knots and bearing 0..<360 degrees.")
    for index in range(args.count):
        sentences = nmea_sentences(latitude, longitude, datetime.now(timezone.utc),
                                   args.speed_knots, args.bearing, args.altitude)
        for sentence_type, sentence in zip(("GPGGA", "GPRMC"), sentences):
            try:
                send_console_input(args.adb, args.serial, ["nmea", sentence])
            except ConsoleInputError as error:
                print(json.dumps({"event": "feed_failed", "utc": datetime.now(timezone.utc).isoformat(),
                                  "sample": index + 1, "total": args.count,
                                  "phase": args.point or ("custom" if supplied_coordinates else "start"),
                                  "progress": None, "input": sentence_type,
                                  "errorCategory": error.category, "consoleAccepted": False,
                                  "arrivalVerified": False}), flush=True)
                return 1
        print(json.dumps({"gnssPair": index + 1, "total": args.count,
                          "consoleAccepted": True, "arrivalVerified": False}), flush=True)
        if index + 1 < args.count:
            time.sleep(args.interval)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
