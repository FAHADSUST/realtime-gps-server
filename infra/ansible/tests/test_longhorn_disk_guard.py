"""The longhorn_node role formats a disk only if its safety check passes. This evaluates that check
(the assert's conditions, read from the role) against realistic `lsblk --json` output.

Run from the repository root: python3 -m unittest discover -s infra/ansible/tests
"""
import pathlib
import unittest

import jinja2
import yaml

TASKS = pathlib.Path(__file__).resolve().parents[1] / "roles" / "longhorn_node" / "tasks" / "main.yml"


def _conditions():
    tasks = yaml.safe_load(TASKS.read_text(encoding="utf-8"))
    block = next(t for t in tasks if t.get("name") == "Prepare the Longhorn data disk")["block"]
    check = next(t for t in block if "ansible.builtin.assert" in t)
    return check["ansible.builtin.assert"]["that"]


def _device(name, type_="disk", fstype=None, pttype=None, mountpoints=(None,), children=None):
    device = {"name": name, "type": type_, "fstype": fstype, "pttype": pttype, "mountpoints": list(mountpoints)}
    if children:
        device["children"] = children
    return device


class LonghornDiskGuard(unittest.TestCase):
    conditions = _conditions()
    env = jinja2.Environment()

    def accepted(self, device):
        context = {"longhorn_node_disk": device, "longhorn_data_path": "/var/lib/longhorn"}
        return all(self.env.compile_expression(c)(**context) for c in self.conditions)

    def test_accepts_an_empty_disk(self):
        self.assertTrue(self.accepted(_device("sdb")))

    def test_accepts_its_own_disk_again(self):
        self.assertTrue(self.accepted(_device("sdb", fstype="ext4", mountpoints=["/var/lib/longhorn"])))
        self.assertTrue(self.accepted(_device("sdb", fstype="ext4")))

    def test_refuses_the_os_disk(self):
        os_disk = _device("sda", pttype="gpt",
                          children=[{"name": "sda1", "type": "part", "fstype": "ext4", "mountpoints": ["/"]}])
        self.assertFalse(self.accepted(os_disk))

    def test_refuses_anything_else_that_holds_data(self):
        for label, device in {
            "partition table": _device("sdb", pttype="gpt"),
            "another filesystem": _device("sdb", fstype="xfs"),
            "mounted elsewhere": _device("sdb", fstype="ext4", mountpoints=["/srv/data"]),
            "a partition, not a disk": _device("sdb1", type_="part"),
            "an LVM physical volume": _device("sdb", fstype="LVM2_member"),
        }.items():
            with self.subTest(label):
                self.assertFalse(self.accepted(device))


if __name__ == "__main__":
    unittest.main()
