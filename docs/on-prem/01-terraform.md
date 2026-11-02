# 01 — Terraform: machines

Terraform creates the cluster's virtual machines on Proxmox VE and writes the Ansible inventory.
Code: [`infra/terraform`](../../infra/terraform).

| File | Purpose |
|---|---|
| `modules/proxmox-vm/` | One VM: full clone of the template, static IP, optional Longhorn disk. The only hypervisor-specific code |
| `variables.tf` | Every input, with validations for the mistakes that are expensive later |
| `main.tf` | One module instance per entry in `nodes`, plus the address-plan check |
| `inventory.tf` | Writes `infra/ansible/inventory/hosts.yml` |
| `terraform.tfvars.example` | The default topology: 3 control planes, 3 workers |
| `scripts/create-ubuntu-template.sh` | Builds the Ubuntu 24.04 cloud-init template, once per Proxmox cluster |

---

## Prerequisites

**Proxmox VE 8 or newer** with a storage pool for VM disks (`local-lvm` by default) and a bridge on
the node network (`vmbr0`).

**An API token for Terraform.** On any Proxmox node:

```bash
pveum user add terraform@pve --comment "Terraform: realtime-gps cluster"
pveum aclmod / -user terraform@pve -role PVEVMAdmin
pveum aclmod /storage -user terraform@pve -role PVEDatastoreUser
pveum aclmod /sdn/zones -user terraform@pve -role PVESDNUser
pveum user token add terraform@pve provider --privsep 0
```

The last command prints the secret once. Scope the `/` grant to a resource pool if the Proxmox
cluster hosts other workloads.

**The VM template.** Copy `scripts/create-ubuntu-template.sh` to the Proxmox node that will hold it
and run it as root. It downloads the Ubuntu 24.04 cloud image, verifies its checksum, bakes in
`qemu-guest-agent`, and registers template `9000`:

```bash
apt install -y libguestfs-tools
TEMPLATE_ID=9000 STORAGE=local-lvm BRIDGE=vmbr0 ./create-ubuntu-template.sh
```

The guest agent matters: without it Proxmox cannot see a VM's addresses or shut it down cleanly,
and Terraform waits on it.

**On your workstation:** Terraform 1.9 or newer.

---

## Configure

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
export TF_VAR_proxmox_api_token='terraform@pve!provider=<secret>'
```

Edit `terraform.tfvars`: Proxmox endpoint and node names, the network, your SSH public key, and the
node list. The token is deliberately not a file setting — `terraform.tfvars` is git-ignored, but an
environment variable cannot be committed by accident at all.

### The address plan

Terraform owns **every address on the node network**: each VM's IP, the Kubernetes API virtual IP,
the MetalLB pool and the ingress address. Keeping them in one place is what makes it possible to
check them against each other:

| Check | Why it exists |
|---|---|
| Odd number of control planes | etcd needs a majority; two control planes tolerate zero failures |
| At least one worker, each with a data disk | Control planes do not run workloads; Longhorn stores volumes on the data disk |
| Unique IPs and VM ids | A duplicate surfaces much later as an unreachable node |
| API VIP and ingress IP are not node addresses | A clash looks like a flapping API or an ingress answering from the wrong machine |

Ansible never re-declares these addresses; it reads them from the inventory Terraform writes.

---

## Run

```bash
terraform init
terraform plan
terraform apply
```

`apply` creates the VMs and writes `infra/ansible/inventory/hosts.yml`:

```yaml
all:
  vars:
    ansible_user: ubuntu
    kubernetes_api_vip: 10.10.0.10
    metallb_address_pool: 10.10.0.200-10.10.0.230
    ingress_lb_ip: 10.10.0.200
    node_network_cidr: 10.10.0.0/24
  children:
    k8s_cluster:
      children:
        control_plane: { hosts: { cp-01: { ansible_host: 10.10.0.11 }, ... } }
        workers:       { hosts: { wk-01: { ansible_host: 10.10.0.21 }, ... } }
```

Hosts are emitted in name order, so the first control plane — the one kubeadm initialises — is
always the same machine. Next: [02 — Ansible](02-ansible.md).

---

## Day 2

**Add a node:** add an entry to `nodes`, `terraform apply`, then run the Ansible join playbook. The
existing VMs are untouched: every node is addressed by name, not by position in a list.

**Remove a node:** drain and delete it in Kubernetes first (and let Longhorn move its replicas),
then remove its entry and apply. Removing it from Terraform first would cut a live node.

**Resize a node:** a CPU or memory change needs a reboot, and Terraform is not allowed to reboot
nodes on its own (`allow_vm_reboot = false`), so a plain `apply` stops with an error rather than
taking a node down unannounced. Drain the node, then apply just that node with reboots allowed:

```bash
kubectl drain wk-01 --ignore-daemonsets --delete-emptydir-data
terraform apply -target='module.node["wk-01"]' -var allow_vm_reboot=true
kubectl uncordon wk-01
```

**State.** State is local by default. It never contains the API token (provider settings are not
stored in state), but it does describe every VM, so keep it private and back it up: losing it means
re-importing every machine. For a team, use a shared backend that also lives on-prem;
`versions.tf` has a PostgreSQL example.

**Another hypervisor.** Replace `modules/proxmox-vm` with an equivalent for vSphere or libvirt that
accepts the same inputs. Its outputs are all that Ansible consumes, so nothing downstream changes.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `apply` hangs at "still creating" for minutes | The template has no guest agent; rebuild it with the script |
| `403 Permission check failed` | The token was created with privilege separation; recreate it with `--privsep 0` |
| VM boots but has no network | Wrong bridge or VLAN in `network`; check the VM's `net0` in the Proxmox UI |
| kubeadm later rejects a node for a duplicate `product_uuid` | The VM was copied outside Terraform; clones made by Proxmox get unique ids |
