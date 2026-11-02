"""Unit tests for filter_plugins/network.py.

Run from the repository root: python3 -m unittest discover -s infra/ansible/tests
"""
import importlib.util
import pathlib
import sys
import types
import unittest

try:
    from ansible.errors import AnsibleFilterError
except ImportError:  # Ansible not installed (e.g. Windows): a stand-in is enough for these tests.
    errors = types.ModuleType("ansible.errors")

    class AnsibleFilterError(Exception):
        pass

    errors.AnsibleFilterError = AnsibleFilterError
    sys.modules.setdefault("ansible", types.ModuleType("ansible"))
    sys.modules["ansible.errors"] = errors

PLUGIN = pathlib.Path(__file__).resolve().parents[1] / "filter_plugins" / "network.py"
_spec = importlib.util.spec_from_file_location("network_filters", PLUGIN)
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
ipv4_range_without = _module.FilterModule().filters()["ipv4_range_without"]


class Ipv4RangeWithout(unittest.TestCase):

    def test_first_address(self):
        self.assertEqual(ipv4_range_without("10.10.0.200-10.10.0.230", "10.10.0.200"),
                         ["10.10.0.201-10.10.0.230"])

    def test_last_address(self):
        self.assertEqual(ipv4_range_without("10.10.0.200-10.10.0.230", "10.10.0.230"),
                         ["10.10.0.200-10.10.0.229"])

    def test_middle_address_splits_the_range(self):
        self.assertEqual(ipv4_range_without("10.10.0.200-10.10.0.230", "10.10.0.210"),
                         ["10.10.0.200-10.10.0.209", "10.10.0.211-10.10.0.230"])

    def test_single_address_range_leaves_nothing(self):
        self.assertEqual(ipv4_range_without("10.10.0.200-10.10.0.200", "10.10.0.200"), [])

    def test_crossing_an_octet_boundary(self):
        self.assertEqual(ipv4_range_without(" 10.10.0.255-10.10.1.5 ", "10.10.1.0"),
                         ["10.10.0.255-10.10.0.255", "10.10.1.1-10.10.1.5"])

    def test_rejects_bad_input(self):
        for args in [("10.10.0.200-10.10.0.230", "10.10.0.199"),   # outside the range
                     ("10.10.0.230-10.10.0.200", "10.10.0.210"),   # reversed range
                     ("10.10.0.0/24", "10.10.0.1"),                # a CIDR, not a range
                     ("10.10.0.200-10.10.0.230", "not-an-ip")]:
            with self.subTest(args=args), self.assertRaises(AnsibleFilterError):
                ipv4_range_without(*args)


if __name__ == "__main__":
    unittest.main()
