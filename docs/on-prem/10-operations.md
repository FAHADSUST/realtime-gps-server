# 10 — Operations: upgrades, backups and recovery

Day 2 on this platform is changing code and running the step that applies it. Nothing is changed
by hand that a later run would undo.

| To change | Edit | Apply with |
|---|---|---|
| Machines, addresses | `infra/terraform/terraform.tfvars` | `terraform apply`, then `ansible-playbook site.yml --limit <new nodes>` |
| Kubernetes version | `group_vars/all/versions.yml` | `ansible-playbook upgrade.yml` |
| containerd, add-ons installed by Ansible | `group_vars/all/versions.yml` | `ansible-playbook site.yml --tags <layer>` |
| Observability, registry, application | `deploy/kubernetes/gitops`, `deploy/kubernetes/gps`, `deploy/kubernetes/registry` | Merge; Argo CD syncs |
| Secrets | `group_vars/all/vault.yml` | `ansible-playbook site.yml --tags secrets` (or the layer that uses it) |

Every change is checked first with `./scripts/validate-platform.sh` — CI runs it on each pull
request — and Ansible runs need `--ask-vault-pass`.

---

## Routine

| Task | When | How |
|---|---|---|
| Watch alerts | Continuously | Alertmanager and Grafana; nothing sends them anywhere until a receiver is configured (see [08 — Observability](08-observability.md#prometheus-and-alertmanager)). `Watchdog` always fires: it shows rules reach Alertmanager |
| Copy etcd snapshots off the cluster | Daily | From a backup host, e.g. `rsync -a --rsync-path='sudo rsync' ubuntu@10.10.0.11:/var/backups/etcd/ /srv/backup/rls/etcd/` |
| Check Kubernetes certificates | Monthly | `sudo kubeadm certs check-expiration` on a control plane. Every upgrade renews them; otherwise see [Expired certificates](#expired-certificates) |
| Free registry space | Monthly | `registry garbage-collect` (see [09 — Application](09-application.md#the-image-registry)) |
| OS updates | Weekly | Unattended upgrades install security fixes; nodes that need a reboot are rebooted one by one, below |
| Proxmox host updates | Monthly | One host at a time, never with two control planes on it: [00 — Proxmox VE](00-proxmox.md#patching-a-host) |
| Practise a restore | Quarterly | Restore an etcd snapshot and a Longhorn backup into a scratch cluster. An untested backup is a hope |

**Rebooting a node**, one at a time:

```bash
kubectl drain wk-01 --ignore-daemonsets --delete-emptydir-data
ssh ubuntu@10.10.0.21 sudo reboot
kubectl wait --for=condition=Ready node/wk-01 --timeout=10m
kubectl uncordon wk-01
kubectl -n longhorn-system get volumes.longhorn.io     # all "healthy" before the next worker
```

---

## Upgrading

### Kubernetes

One minor version at a time (1.36 → 1.37, never 1.36 → 1.38).

1. **Check compatibility.** The target must be supported by Cilium (its "Kubernetes compatibility"
   page), Longhorn and kube-prometheus-stack. Read the Kubernetes changelog's urgent upgrade notes.
2. **Set the version.** `kubernetes_version` in `versions.yml`. Commit.
3. **Upgrade:**

   ```bash
   cd infra/ansible
   ansible-playbook upgrade.yml --ask-vault-pass
   ```

   It refuses a target more than one minor version ahead, checks every node is Ready and takes an
   etcd snapshot. Then, one node at a time and control planes first, it drains the node, upgrades
   kubeadm, runs `kubeadm upgrade apply` on the first control plane (`kubeadm upgrade node`
   elsewhere), upgrades the kubelet and kubectl, and returns the node to service. It is safe to run
   again: nodes already on the target are skipped. kube-proxy stays off — kubeadm keeps that in the
   cluster's configuration.
4. **Finish.** `kubeadm config images list` on a control plane shows the pause image the new
   version expects; if it differs, set `pause_image` in `versions.yml`. Then
   `ansible-playbook site.yml --tags nodes,kubeadm --ask-vault-pass` updates containerd's pinned
   image and the admin kubeconfig copies, whose certificates the upgrade renewed.

### containerd and runc

Set the versions and their checksums in `versions.yml` (see
[02 — Ansible](02-ansible.md#containerd)), then `ansible-playbook site.yml --tags nodes --forks 1`
to restart containerd on one node at a time. Running containers are not affected.

### Add-ons installed by Ansible

Change the chart version in `versions.yml`, read the release's upgrade notes, run its tag
(`cilium`, `metallb`, `longhorn`, `ingress`, `tls`, `argocd`, `crds`). Specifics:

| Component | Note |
|---|---|
| Cilium | Stay within its supported Kubernetes versions; upgrade one minor version at a time |
| Longhorn | One minor version at a time, never skipping one |
| Prometheus Operator CRDs | Move together with kube-prometheus-stack: the CRDs first (`--tags crds`), then the stack in Git |
| Ingress-NGINX | No further releases. Plan the move to the Gateway API (see [04 — Networking](04-networking.md#moving-off-ingress-nginx)) |

### Applications deployed by Argo CD

Chart versions live in `deploy/kubernetes/gitops/values.yaml`. Change one, check with
`./scripts/validate-platform.sh charts`, merge. The GPS services are released the same way, by image
tag (see [09 — Application](09-application.md#releasing)).

---

## Backups

| What | Holds | Where it is kept |
|---|---|---|
| etcd snapshots | Every Kubernetes object: Secrets, Argo CD's state, the cluster CA key | `/var/backups/etcd` on each control plane, nightly, newest seven — **copy them off the cluster** |
| Longhorn backups | Volume data: MySQL, Redis, RabbitMQ, registry, Prometheus, Loki, Grafana | A backup target outside the cluster — configure it and recurring jobs (see [05 — Storage](05-storage.md#backups)) |
| The cluster CA | The key every certificate is signed with | In the etcd snapshots; also export it once: `kubectl -n cert-manager get secret rls-root-ca -o yaml` to offline storage |
| The vault and its password | Every secret | `vault.yml` is git-ignored: keep a copy, and the password in a password manager |
| Terraform state | Which VMs Terraform manages | `infra/terraform/terraform.tfstate` locally, unless a remote backend is configured: keep a copy |

A snapshot or backup contains secrets in readable form: store it encrypted, with restricted access.

---

## Recovery

### A worker fails

Its pods restart on the other workers; volumes reattach there (Longhorn deletes pods stuck on the
dead node). To replace it under the same name:

```bash
kubectl delete node wk-02
terraform apply -replace='module.node["wk-02"].proxmox_virtual_environment_vm.this'
ansible-playbook site.yml --limit wk-02 --ask-vault-pass
```

### A control plane fails

Two of three control planes keep etcd's quorum and the VIP moves; the cluster keeps working. To
replace `cp-03`, first remove its etcd member, so the new one can join:

```bash
kubectl -n kube-system exec etcd-cp-01 -- etcdctl --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key member list
kubectl -n kube-system exec etcd-cp-01 -- etcdctl <same flags> member remove <id of cp-03>
kubectl delete node cp-03
terraform apply -replace='module.node["cp-03"].proxmox_virtual_environment_vm.this'
ansible-playbook site.yml --limit cp-03 --ask-vault-pass
```

### A Proxmox host fails

Every VM on it goes down at once; [00 — Proxmox VE](00-proxmox.md#what-a-host-failure-does) shows
what that means for each layout. With two hosts, losing the one with two control planes stops the
Kubernetes API until it returns — or until etcd is restored on the surviving control plane, below.

If the host is gone for good:

1. On a surviving host, remove it from the Proxmox cluster: `pvecm delnode <host>`. With a QDevice,
   run `pvecm qdevice remove` first — nodes can't be added or removed while one is set up.
2. Install Proxmox VE on the replacement with the same name and addresses, and join it
   ([00](00-proxmox.md#3-create-the-cluster)). With two hosts, set up the QDevice again.
3. Rebuild its VMs as for a lost worker or control plane above: remove the Kubernetes nodes (and the
   etcd members of control planes), `terraform apply` to recreate the VMs, then
   `ansible-playbook site.yml --limit <nodes> --ask-vault-pass`.

### etcd quorum is lost

When two of three control planes are gone, the API stops. Restore the cluster state on one control
plane from a snapshot, then rebuild the others around it. Practise this before you need it.

1. On **every** remaining control plane, stop the control plane:
   `sudo mkdir -p /root/manifests && sudo mv /etc/kubernetes/manifests/*.yaml /root/manifests/`
2. On `cp-01`, restore the newest snapshot into a fresh data directory, with the tools in the etcd
   image (`etcd:3.6.8-0` for Kubernetes 1.36):

   ```bash
   sudo ctr -n k8s.io run --rm \
     --mount type=bind,src=/var/backups/etcd,dst=/backup,options=rbind:ro \
     --mount type=bind,src=/var/lib,dst=/var/lib,options=rbind:rw \
     registry.k8s.io/etcd:3.6.8-0 etcd-restore \
     etcdutl snapshot restore /backup/<snapshot>.db --data-dir /var/lib/etcd-restored \
       --name cp-01 --initial-cluster cp-01=https://10.10.0.11:2380 \
       --initial-advertise-peer-urls https://10.10.0.11:2380
   sudo mv /var/lib/etcd /var/lib/etcd.broken && sudo mv /var/lib/etcd-restored /var/lib/etcd
   sudo mv /root/manifests/*.yaml /etc/kubernetes/manifests/
   ```

3. Wait for `kubectl get nodes` to answer. The restored etcd has one member, `cp-01`.
4. On each other control plane: `sudo kubeadm reset -f`, `kubectl delete node <name>`, then
   `ansible-playbook site.yml --limit cp-02,cp-03 --ask-vault-pass` joins them again.

Objects created after the snapshot are lost; Argo CD puts back whatever Git describes.

### A volume is lost or damaged

Restore it from its Longhorn backup: Longhorn UI → **Backup** → the volume → **Restore**, then
**Create PV/PVC** with the original claim's name, after scaling its workload to zero (for
StatefulSets, delete the old claim first). Start the workload again.

### The whole cluster is lost

1. `terraform apply` recreates the machines.
2. Before the TLS layer, restore the CA: re-create the namespace and apply the exported
   `rls-root-ca` Secret. cert-manager adopts it, and every client keeps trusting the cluster.
3. `ansible-playbook site.yml --ask-vault-pass`. Argo CD redeploys everything in Git.
4. Restore the volumes from their Longhorn backups, as above.

### Expired certificates

kubeadm's certificates last a year and are renewed by every upgrade. If they expire, the API stops
answering. On each control plane in turn:

```bash
sudo kubeadm certs renew all
sudo mkdir -p /root/manifests && sudo mv /etc/kubernetes/manifests/*.yaml /root/manifests/
sleep 20 && sudo mv /root/manifests/*.yaml /etc/kubernetes/manifests/
```

Then `ansible-playbook site.yml --tags kubeadm --ask-vault-pass` hands out fresh admin kubeconfigs.

---

## Troubleshooting index

| Area | Guide |
|---|---|
| Proxmox, Terraform | [01 — Terraform](01-terraform.md#troubleshooting) |
| Ansible, node preparation, mirrors | [02 — Ansible](02-ansible.md#troubleshooting) |
| Control plane, VIP, joins, Cilium | [03 — Kubernetes](03-kubernetes.md#troubleshooting) |
| MetalLB, Ingress-NGINX, DNS | [04 — Networking](04-networking.md#troubleshooting) |
| Longhorn | [05 — Storage](05-storage.md#troubleshooting) |
| Certificates, operator UIs | [06 — TLS](06-tls.md#troubleshooting) |
| Argo CD | [07 — GitOps](07-gitops.md#troubleshooting) |
| Prometheus, Grafana, Loki, Alloy | [08 — Observability](08-observability.md#troubleshooting) |
| The GPS services, registry | [09 — Application](09-application.md#troubleshooting) |
