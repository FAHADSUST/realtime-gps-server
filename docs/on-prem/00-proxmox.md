# 00 — Proxmox VE: two or more hosts

On a single Proxmox host, that host is a single point of failure for the whole platform. This guide
builds a Proxmox cluster of **two or more hosts** and spreads the Kubernetes VMs across them, so a
hardware failure takes out only part of the platform — and says exactly which part. It is done once,
before [01 — Terraform](01-terraform.md).

---

## How many hosts?

| Hosts | Proxmox cluster | Kubernetes control plane (3 VMs) | Verdict |
|---|---|---|---|
| 1 | — | All on one host: losing it stops everything | A lab |
| 2 | Needs a **QDevice** — a third vote from a small machine outside the cluster | One host carries two control planes. Losing *that* host stops the Kubernetes API — what runs on the other host keeps serving, but nothing is rescheduled; losing the other host is survivable | The minimum. Know the asymmetry |
| 3 | Keeps quorum on its own | One control plane per host: losing **any one** host is survivable | Recommended. The third host can be small — it only runs `cp-03` |

**Why two hosts can't fully protect the control plane:** etcd needs a majority of its three
members. Spread over two hosts, one host always holds two of them, and losing that host leaves one —
no majority. Proxmox has the same problem with its own votes, which the QDevice solves; etcd has no
equivalent, so for full host-level availability the third control plane needs a third machine.

---

## The layout

The two-host layout, with the optional third host in brackets:

```
                 node network 10.10.0.0/24 — one VLAN, reached through vmbr0 on every host
   ┌───────────────────────────────────┐   ┌───────────────────────────────────┐   ┌──────────────────────┐
   │ pve-01  10.10.0.2                 │   │ pve-02  10.10.0.3                 │   │ [pve-03  10.10.0.4]  │
   │   cp-01 .11    cp-03 .13          │   │   cp-02 .12                       │   │   [cp-03 .13]        │
   │   wk-01 .21                       │   │   wk-02 .22    wk-03 .23          │   │                      │
   └─────────────────┬─────────────────┘   └─────────────────┬─────────────────┘   └──────────┬───────────┘
                     └────────── corosync link 10.10.1.0/24, a dedicated NIC ─────────────────┘

   qdev-01  10.10.0.9 — the QDevice: two-host clusters only, outside both hosts
```

With a third host, `cp-03` moves from `pve-01` to `pve-03`, and the QDevice is no longer needed.

| Address | Purpose |
|---|---|
| `10.10.0.2` – `10.10.0.4` | The Proxmox hosts' own addresses: web UI, API, SSH |
| `10.10.0.9` | The QDevice (two hosts only) |
| `10.10.1.0/24` | Corosync, the cluster's heartbeat: one address per host on a dedicated NIC |
| `10.10.0.0/24` | The node network, as in the [address plan](README.md#network-plan) |

Terraform does not know about the hosts' addresses: keep `.2` – `.9` out of the VM addresses and
the MetalLB pool. In production, put the hosts' management interfaces on a VLAN of their own, so
the Kubernetes VMs can't reach the Proxmox API at all.

**Sizing, for the default VM sizes.** All six VMs need 36 vCPUs, 72 GiB of memory and about 1 TB of
disk. Across two hosts that is roughly half each, plus room for one control plane moved over during
maintenance: **16 cores, 64 GiB of memory and a 1 TB SSD per host** is comfortable. A third host
running only `cp-03` needs 4 cores, 16 GiB and 128 GB.

---

## 1. Install Proxmox VE on every host

- **Proxmox VE 9.2**, the current release (Debian 13). Proxmox VE 8 reached end of life in August
  2026. Every host runs the same version.
- **A host name and a static address each:** `pve-01.rls.internal` at `10.10.0.2`, and so on. Each
  host must resolve the others — through DNS, or `/etc/hosts` on every host.
- **The same VM storage on every host.** Installing on ext4 or xfs creates `local-lvm`; installing on
  ZFS creates `local-zfs`. Use one kind everywhere: Terraform's `datastore_id` names a storage that
  must exist on every host.
- **Updates:** under *Node → Updates → Repositories*, add the *No-Subscription* repository and
  disable the enterprise ones — or add a subscription key. Then `apt update && apt full-upgrade` and
  reboot.
- **Time** is kept by chrony, installed by default. Point it at your own NTP server if the hosts
  have no internet access: the cluster needs synchronised clocks.

---

## 2. Network every host the same way

`/etc/network/interfaces` on `pve-01` — the other hosts differ only in their addresses:

```
auto lo
iface lo inet loopback

iface eno1 inet manual

# The node network: the host's own address, and the bridge every Kubernetes VM attaches to.
auto vmbr0
iface vmbr0 inet static
    address 10.10.0.2/24
    gateway 10.10.0.1
    bridge-ports eno1
    bridge-stp off
    bridge-fd 0

# Corosync only: a second NIC on its own switch ports or VLAN.
auto eno2
iface eno2 inet static
    address 10.10.1.2/24
```

Apply it with `ifreload -a`. Three things matter:

- **`vmbr0` on every host reaches the same layer-2 network.** kube-vip and MetalLB announce their
  addresses with ARP, and Cilium's native routing expects every node on one segment: a VM on
  `pve-01` and a VM on `pve-02` must be direct neighbours. If the node network is a tagged VLAN, make
  the bridge VLAN-aware (`bridge-vlan-aware yes`) and set `network.vlan_id` in Terraform.
- **Corosync needs low, steady latency — under 5 ms, LAN only** — not bandwidth. A dedicated 1 Gbit
  NIC is enough; sharing it with storage or migration traffic is what breaks clusters. Check before
  creating the cluster: `ping -c 100 10.10.1.3` from `pve-01`.
- **Hosts reach each other on UDP 5405–5412** (corosync), TCP 22 (SSH) and TCP 8006 (the API,
  used to join). The Proxmox firewall opens corosync's ports by itself; check any firewall between
  the hosts.

---

## 3. Create the cluster

On `pve-01`, with corosync on its dedicated link and the node network as a fallback:

```bash
pvecm create rls-pve --link0 10.10.1.2 --link1 10.10.0.2
```

On `pve-02` — which must not hold any VM or container yet: a joining host's configuration is
replaced by the cluster's:

```bash
pvecm add 10.10.0.2 --link0 10.10.1.3 --link1 10.10.0.3
```

It asks for `pve-01`'s root password and shows its certificate fingerprint to confirm. Corosync
prefers the lowest-numbered link, so heartbeats use the dedicated link and fall back to the node
network only if it fails. Repeat for `pve-03` if there is one. Then, on any host:

```bash
pvecm status      # Quorate: Yes, one line per host under "Membership information"
```

The cluster name (`rls-pve`) can't be changed later. User accounts, API tokens and storage
definitions now live in `/etc/pve`, shared by every host: create Terraform's token once.

---

## 4. Two hosts: add the QDevice

Each host has one vote, and quorum needs a majority — two of two. **When one of two hosts fails, the
survivor loses quorum**, and the cluster turns read-only: running VMs carry on, but nothing can be
started, stopped or changed — Terraform included — until the other host returns. A QDevice gives the
cluster a third vote from a small machine outside it.

On the QDevice machine — any small Debian or Ubuntu box that is *not* one of the hosts: a mini PC, or a
VM on other infrastructure:

```bash
apt install corosync-qnetd
```

On every host:

```bash
apt install corosync-qdevice
```

Then, on `pve-01` (it needs root SSH access to the QDevice — by key, or by password during setup):

```bash
pvecm qdevice setup 10.10.0.9
pvecm status      # Expected votes: 3, Flags: Quorate Qdevice
```

- The QDevice talks to the cluster over TCP 5403 and has no latency requirement.
- If the QDevice fails, the cluster is exactly where it was without one: no worse.
- **Only for an even number of hosts.** To add a third host later, first `pvecm qdevice remove`, then
  add the host, and leave the QDevice out: three hosts keep quorum on their own.

---

## 5. Storage and migration

**Local storage on each host is the right choice here.** Data is already replicated where it
matters: etcd across the control planes, Longhorn across the workers. The hypervisor doesn't need to
do it again.

| Option | Verdict |
|---|---|
| Local LVM-thin or ZFS on each host | **Recommended.** Fast, simple, no shared component to fail |
| Ceph on the Proxmox hosts (3+ hosts) | Not under Longhorn: replication on top of replication keeps nine copies of everything and adds latency. If you want Ceph, use it from Kubernetes directly (Ceph CSI) instead of Longhorn |
| NFS or iSCSI shared storage | Only needed for Proxmox HA, and the storage itself becomes a single point of failure unless it is highly available too |

**Leave Proxmox HA off for these VMs.** Kubernetes already handles a lost node by rescheduling its
pods, and with local disks Proxmox couldn't restart a VM elsewhere anyway.

**Migration traffic stays off the corosync link.** Proxmox sends migrations over the cluster network
by default; point them at the node network — or a faster third network if the hosts have one — in
`/etc/pve/datacenter.cfg` (or *Datacenter → Options → Migration Settings*):

```
migration: secure,network=10.10.0.0/24
```

Live migration between hosts is only guaranteed between CPUs of the same vendor. Terraform's default
CPU type, `x86-64-v2-AES`, hides the differences between CPU generations so that VMs can move.

---

## 6. Terraform: place each VM on a host

Everything in [01 — Terraform](01-terraform.md) applies unchanged, with three differences:

- **One API endpoint is enough** — any host manages the whole cluster. If that host is down, point
  `proxmox_endpoint` at another.
- **The template is created once, on `pve-01`.** A VM placed on another host is cloned on `pve-01`
  and then migrated there with its disk — the provider does this by itself within a cluster — before
  its data disk is added on the target host.
- **Each VM names its host** with `proxmox_node`:

```hcl
default_proxmox_node = "pve-01"

template = { vm_id = 9000, node_name = "pve-01" }

nodes = {
  "cp-01" = { role = "control_plane", proxmox_node = "pve-01", vm_id = 1011, ip = "10.10.0.11", cores = 4, memory_mb = 8192, os_disk_gb = 60 }
  "cp-02" = { role = "control_plane", proxmox_node = "pve-02", vm_id = 1012, ip = "10.10.0.12", cores = 4, memory_mb = 8192, os_disk_gb = 60 }
  "cp-03" = { role = "control_plane", proxmox_node = "pve-01", vm_id = 1013, ip = "10.10.0.13", cores = 4, memory_mb = 8192, os_disk_gb = 60 }   # "pve-03" with three hosts
  "wk-01" = { role = "worker", proxmox_node = "pve-01", vm_id = 1021, ip = "10.10.0.21", cores = 8, memory_mb = 16384, os_disk_gb = 80, data_disk_gb = 200 }
  "wk-02" = { role = "worker", proxmox_node = "pve-02", vm_id = 1022, ip = "10.10.0.22", cores = 8, memory_mb = 16384, os_disk_gb = 80, data_disk_gb = 200 }
  "wk-03" = { role = "worker", proxmox_node = "pve-02", vm_id = 1023, ip = "10.10.0.23", cores = 8, memory_mb = 16384, os_disk_gb = 80, data_disk_gb = 200 }
}
```

The host carrying two control planes gets fewer workers, so each host carries about half the load.

**Moving a VM between hosts:** change its `proxmox_node` and apply — Terraform migrates it, online
and with its disks, instead of replacing it. A VM moved by hand in Proxmox is safe too: the next
`terraform apply` moves it back, unless `proxmox_node` is first updated to its new host.

---

## What a host failure does

With the two-host layout above:

| Lost | Proxmox | Kubernetes API | Applications |
|---|---|---|---|
| `pve-02` (`cp-02`, `wk-02`, `wk-03`) | Stays quorate — the QDevice's vote | Stays up: `cp-01` and `cp-03` are two of three | Pods from `wk-02` and `wk-03` restart on `wk-01` — as far as its capacity allows. Volumes run on their one surviving replica |
| `pve-01` (`cp-01`, `cp-03`, `wk-01`) | Stays quorate | **Down**: one etcd member of three | What runs on `wk-02` and `wk-03` keeps serving, but nothing is rescheduled until `pve-01` returns — or etcd is restored on `cp-02` ([10 — Operations](10-operations.md#etcd-quorum-is-lost)) |
| The QDevice | Stays quorate: two votes of three | Unaffected | Unaffected |

With three hosts, one control plane each, losing any single host is the first row: everything
keeps running and the lost VMs' pods move to the remaining workers.

**Capacity is the other half of availability.** When a host fails, the surviving workers must carry
its pods. With three workers on two hosts, size them so the one-worker side can carry everything —
or run four workers, two per host.

**Kubernetes doesn't know which VMs share a host.** Its pod spreading and Longhorn's replica
placement see VMs, not hosts. Labelling each node with `topology.kubernetes.io/zone=<host>` would
let both spread across hosts too; it is not automated yet.

---

## Patching a host

One host at a time, and never with two control planes on it:

1. **If the host carries two control planes, move one away first,** so the reboot leaves etcd two of
   three: `qm migrate 1013 pve-02 --online --with-local-disks` on `pve-01` (`cp-03`; its 60 GiB
   disk takes minutes to copy).
2. **Drain its Kubernetes nodes:** `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`
   for each VM on the host.
3. **Update and reboot the host:** `apt update && apt full-upgrade && reboot`. Proxmox shuts its VMs
   down cleanly through the guest agent and starts them again at boot.
4. **Return the nodes:** `kubectl uncordon` each, and wait until every Longhorn volume is healthy
   (`kubectl -n longhorn-system get volumes.longhorn.io`) before the next host.
5. **Move the control plane back:** `terraform apply` migrates `cp-03` to its `proxmox_node` again.

With three hosts, step 1 isn't needed.

**VM-level backups** (`vzdump`, Proxmox Backup Server) aren't needed for these VMs: they are rebuilt
from code, and their data is protected by etcd snapshots and Longhorn backups
([10 — Operations](10-operations.md#backups)).

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `pvecm add` refuses to join | The joining host already holds a VM or container, or can't reach the first host on TCP 8006 and UDP 5405–5412 |
| `pvecm status` shows `Quorate: No` on the surviving host | A two-host cluster without a QDevice lost one host. To make changes before it returns: `pvecm expected 1` — only while the other host is truly down |
| QDevice listed as `NA` (not alive) in `pvecm status` | TCP 5403 to the QDevice is blocked |
| Terraform: clone or migration fails with a storage error | `datastore_id` doesn't exist on the target host: every host needs the same storage name |
| Terraform fails after a host went down | `proxmox_endpoint` points at the dead host: use another, or a DNS name for all of them |
| Live migration fails between hosts | CPUs of different vendors, or a VM `cpu_type` of `host`: keep `x86-64-v2-AES` |
| VMs on different hosts can't reach each other, or the VIP flaps | `vmbr0` is not on the same layer-2 network on every host: check the switch ports and VLANs |

Next: [01 — Terraform](01-terraform.md).
