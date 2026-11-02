# 05 — Storage: Longhorn

Longhorn turns the workers' data disks into replicated block storage. Every volume keeps three
copies on three different workers, so a node or a disk can fail without losing data, and pods with
volumes can move between workers. It is the cluster's default StorageClass: MySQL, Redis, RabbitMQ,
Prometheus and Loki all store their data on it. Code: roles `longhorn_node` and `longhorn` in
[`infra/ansible/roles`](../../infra/ansible/roles).

---

## Disks

Each worker has a second disk (200 GiB in the default topology) used only by Longhorn. Terraform
creates it and passes its stable device path to Ansible as `longhorn_disk`; `longhorn_node` formats
it ext4 (no blocks reserved for root) and mounts it at `/var/lib/longhorn`.

Formatting cannot be undone, so the role looks before it formats. `lsblk` must show a whole disk
with no partition table, no partitions, no mount elsewhere, and either no filesystem or the ext4
this role created. A wrong device name — the OS disk, a disk holding data, an LVM volume — stops
the run with nothing changed.

**Capacity.** With three workers and three replicas, every byte is stored three times: usable
capacity is about one disk's worth. Longhorn stops placing replicas on a disk once less than 25 %
of it is free, so the default topology holds roughly **150 GiB** of volume data. More workers, or
bigger disks, raise it.

---

## What runs on the nodes

| `longhorn_node` installs | For |
|---|---|
| `open-iscsi`, `iscsid` running, `iscsi_tcp` module | Volumes attach to the node as iSCSI devices |
| `nfs-common` | ReadWriteMany volumes (served over NFS) and NFS backup targets |
| `cryptsetup`, `dmsetup`, `dm_crypt` module | Encrypted volumes |
| A multipath blacklist for SCSI disks | `multipathd`, where installed, would otherwise claim Longhorn's devices and break mounts |

Longhorn runs on the workers only; the control planes keep their `NoSchedule` taint.

---

## Longhorn settings

Values: `roles/longhorn/templates/values.yaml.j2`.

| Setting | Value | Why |
|---|---|---|
| Default StorageClass | `longhorn`, 3 replicas, ext4, `Delete` on release | Replicas on different workers by default |
| Data path | `/var/lib/longhorn` | The mounted data disk |
| Reserved space | 10 % of the disk (Longhorn's default is 30 %) | The default assumes the disk is shared with the OS; this one is dedicated. The 25 % that must stay free is the real guard |
| Node down | `delete-both-statefulset-and-deployment-pod` | Pods on a dead node are deleted, so they restart elsewhere with their volume instead of hanging in Terminating |
| Upgrade checker, usage metrics | off | A private cluster does not call the internet |
| Network policy | Longhorn's default internal-traffic restrictions, plus Prometheus pods from `monitoring` on the manager's port 9500 | That port also serves Longhorn's API, so the exception is as narrow as possible |
| Metrics | `ServiceMonitor` | Volume, node and disk metrics for Prometheus |

`Delete` means deleting a PersistentVolumeClaim deletes its data. For data that must outlive its
claim, use a StorageClass with `reclaimPolicy: Retain`.

---

## Run

```bash
cd infra/ansible
ansible-playbook site.yml --tags longhorn
```

## Verify

```bash
kubectl -n longhorn-system get pods
kubectl -n longhorn-system get nodes.longhorn.io        # every worker Ready and schedulable
kubectl get storageclass                                # longhorn (default)
```

A volume end to end:

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: longhorn-check}
spec:
  accessModes: [ReadWriteOnce]
  resources: {requests: {storage: 1Gi}}
---
apiVersion: v1
kind: Pod
metadata: {name: longhorn-check}
spec:
  containers:
    - name: writer
      image: registry.k8s.io/e2e-test-images/agnhost:2.66.1
      command: [sh, -c, "echo hello > /data/hello && sleep 3600"]
      volumeMounts: [{name: data, mountPath: /data}]
  volumes: [{name: data, persistentVolumeClaim: {claimName: longhorn-check}}]
EOF
kubectl wait --for=condition=Ready pod/longhorn-check --timeout=3m
kubectl exec longhorn-check -- cat /data/hello                       # hello
kubectl -n longhorn-system get volumes.longhorn.io                    # robustness: healthy
kubectl delete pod,pvc longhorn-check
```

**The UI** has no login of its own. Once the TLS layer is in place it is published at
`https://longhorn.rls.internal` behind basic authentication (see
[06 — TLS](06-tls.md#operator-uis)); before that,
`kubectl -n longhorn-system port-forward svc/longhorn-frontend 8080:80` and `http://localhost:8080`.

---

## Backups

Replicas protect against a failed node or disk, not against deleting a volume or corrupting its
data. For that, Longhorn copies snapshots to a **backup target** outside the cluster: an NFS export
or any S3-compatible store (MinIO, Ceph RGW, a NAS). Set it with the chart's `defaultBackupStore`
values, for example:

```yaml
defaultBackupStore:
  backupTarget: nfs://nas.rls.internal:/export/longhorn-backups
```

then define recurring backup jobs (Longhorn → Recurring Jobs) for the volumes that matter.

---

## Day 2

| Task | How |
|---|---|
| Add a worker | Terraform gives it a data disk; `ansible-playbook site.yml --limit wk-04` prepares and joins it, and Longhorn adds its disk automatically |
| Drain a worker | `kubectl drain` works as usual. Longhorn blocks draining the node that holds a volume's last healthy replica until another copy exists |
| Upgrade Longhorn | One minor version at a time (1.13 → 1.14 → …), never skipping one: change `longhorn_chart_version` and re-run the tag. Read the release notes first |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Volume stays `attaching`; events mention iSCSI | `iscsid` is not running on the node: `systemctl status iscsid` |
| `MountVolume.SetUp failed … already mounted or mount point busy` | multipathd took the device. Check `/etc/multipath/conf.d/longhorn.conf` exists, then `systemctl reload multipathd` |
| A node's disk is unschedulable | Less than 25 % free, or the disk is not mounted at `/var/lib/longhorn` (Longhorn recognises its disk by a UUID file and refuses an unfamiliar one) |
| The run stops at "Check that the data disk is safe to format" | `longhorn_disk` points at a device with partitions or another filesystem. Nothing was changed; fix the inventory or wipe the disk yourself |
