import importlib.util
from pathlib import Path
import unittest


source = Path(__file__).resolve().parents[2] / 'scripts' / 'probe_arrival.py'
spec = importlib.util.spec_from_file_location('probe_arrival', source)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeArrivalTransportTest(unittest.TestCase):
    def test_route_and_observe_do_not_start_activity_or_service(self):
        for command, expected in [('route', 'route_sample'), ('observe', 'observe')]:
            intent = probe.probe_intent(command)
            self.assertEqual(intent[:2], ['am', 'broadcast'])
            self.assertIn('ai.nearby.reunion/.DebugProbeReceiver', intent)
            self.assertNotIn('ai.nearby.reunion/.MainActivity', intent)
            self.assertNotIn('startservice', intent)
            self.assertNotIn('start-foreground-service', intent)
            self.assertEqual(intent[-1], expected)

    def test_connect_retains_explicit_user_started_activity_flow(self):
        intent = probe.probe_intent('connect')
        self.assertEqual(intent[:2], ['am', 'start'])
        self.assertIn('ai.nearby.reunion/.MainActivity', intent)
        self.assertIn('connect', intent)

    def test_broadcast_completion_is_not_service_acceptance(self):
        for output in ['', 'Broadcast completed: result=0',
                       'Broadcast completed: result=-3, data="service_not_running"',
                       'Broadcast completed: result=10', 'Permission Denial']:
            self.assertFalse(probe.broadcast_accepted(output))
        self.assertTrue(probe.broadcast_accepted('Broadcast completed: result=1, data="accepted"\n'))

    def test_no_arbitrary_command_or_arrival_injection(self):
        for command in ['arrival', 'route_sample 1,2', 'stop', '']:
            with self.assertRaises(ValueError):
                probe.probe_intent(command)

    def test_activity_command_exit_is_not_launch_success(self):
        self.assertFalse(probe.activity_started('Status: timeout\nComplete\n'))
        self.assertFalse(probe.activity_started('Starting: Intent\nComplete\n'))
        self.assertTrue(probe.activity_started('Starting: Intent\nStatus: ok\nComplete\n'))


if __name__ == '__main__':
    unittest.main()
