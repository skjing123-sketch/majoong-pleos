"""Host-only failure tests: subprocess is stubbed; no emulator is contacted."""
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch


scripts = Path(__file__).resolve().parents[2] / 'scripts'


class GnssFailureTest(unittest.TestCase):
    def capture(self, script, arguments, responses):
        out, err = io.StringIO(), io.StringIO()
        with patch.object(subprocess, 'run', side_effect=responses) as runner, \
             patch('time.sleep'), patch.object(sys, 'argv', [script, *arguments]), \
             patch.object(sys, 'path', [str(scripts), *sys.path]), \
             redirect_stdout(out), redirect_stderr(err):
            with self.assertRaises(SystemExit) as exit_result:
                runpy.run_path(str(scripts / script), run_name='__main__')
        self.assertEqual(err.getvalue(), '')
        self.assertNotIn('secret', out.getvalue())
        self.assertNotIn('$GPGGA', out.getvalue())
        return exit_result.exception.code, [json.loads(line) for line in out.getvalue().splitlines()], runner.call_count

    def test_replay_timeout_reports_exact_incomplete_sample_without_retry_or_trace(self):
        ok = subprocess.CompletedProcess([], 0, stdout='OK\n', stderr='')
        timeout = subprocess.TimeoutExpired(['secret-coordinate-command'], 5,
                                             output='secret-console-output', stderr='secret-stderr')
        code, events, calls = self.capture('drive_gnss_route.py',
            ['--duration', '30', '--hold-start', '0', '--hold-end', '0'], [ok, ok, ok, timeout])
        self.assertEqual(code, 1)
        self.assertEqual(calls, 4)
        failure = events[-1]
        self.assertEqual(failure['event'], 'replay_failed')
        self.assertEqual((failure['sample'], failure['phase'], failure['progress']), (2, 'driving', 0.067))
        self.assertEqual(failure['input'], 'GPRMC')
        self.assertEqual(failure['errorCategory'], 'console_timeout')
        self.assertFalse(failure['arrivalVerified'])
        self.assertFalse(failure['consoleAccepted'])
        self.assertNotIn('replay_complete', [event.get('event') for event in events])

    def test_auto_fix_rejection_stops_before_nmea(self):
        rejected = subprocess.CompletedProcess([], 1, stdout='secret-output', stderr='secret-error')
        code, events, calls = self.capture('drive_gnss_route.py',
            ['--duration', '30', '--sync-auto'], [rejected])
        self.assertEqual((code, calls), (1, 1))
        self.assertEqual(events[-1]['input'], 'geo_fix')
        self.assertEqual(events[-1]['errorCategory'], 'console_rejected')

    def test_feed_timeout_and_unavailable_are_sanitized(self):
        for error, category in [(subprocess.TimeoutExpired('secret-command', 5), 'console_timeout'),
                                (FileNotFoundError('secret-path'), 'console_unavailable')]:
            with self.subTest(category=category):
                code, events, calls = self.capture('feed_gnss.py', ['--count', '2'], [error])
                self.assertEqual((code, calls), (1, 1))
                self.assertEqual(events[-1]['event'], 'feed_failed')
                self.assertEqual(events[-1]['sample'], 1)
                self.assertEqual(events[-1]['errorCategory'], category)
                self.assertFalse(events[-1]['arrivalVerified'])


if __name__ == '__main__':
    unittest.main()
