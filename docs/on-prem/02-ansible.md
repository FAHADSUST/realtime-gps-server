# 02 — Ansible: nodes

Ansible turns the machines Terraform created into Kubernetes nodes: kernel settings, a container
runtime and the Kubernetes packages. Later layers — kubeadm, Cilium and the rest of the platform —
are added to the same playbook, one tagged play each. Code: [`infra/ansible`](../../infra/ansible).

| Path | Purpose |
|---|---|
| `ansible.cfg` | Inventory, role and collection paths; SSH settings |
| `requirements.yml` | Collections, pinned: `ansible.posix`, `community.general`, `kubernetes.core` |
| `inventory/hosts.yml` | Written by Terraform; git-ignored |
| `inventory/hosts.example.yml` | The same shape, for bare metal or machines created another way |
| `inventory/group_vars/all/main.yml` | Cluster-wide settings: names, domains, pod and service networks |
| `inventory/group_vars/all/versions.yml` | Every version the platform installs, with the checksums of downloaded binaries |
| `roles/node_baseline` | Swap, kernel modules, sysctls, time sync, base packages |
| `roles/containerd` | containerd and runc from upstream releases; registry mirrors |
| `roles/kubernetes_packages` | kubelet, kubeadm and kubectl from pkgs.k8s.io, held at one version |
| `site.yml` | The pipeline: one play per layer, each with its own tag |

---

## Prerequisites

**A Linux or macOS control machine** (on Windows, use WSL — Ansible does not run on Windows
itself) with Python 3.11 or newer:

```bash
python3 -m venv ~/.venvs/rls-ansible
. ~/.venvs/rls-ansible/bin/activate
pip install 'ansible-core>=2.18'
cd infra/ansible
ansible-galaxy collection install -r requirements.yml
```

Collections land in `infra/ansible/collections/` (git-ignored), so the playbooks always run with the
versions in `requirements.yml`, whatever else is installed on the machine.

**SSH access** as the inventory's `ansible_user` (`ubuntu`), with the private key matching the
public key Terraform put on the nodes. Load it into your agent with `ssh-add`. The first connection
to each node records its host key; see [Troubleshooting](#troubleshooting) if a rebuilt node is
refused.

**The inventory.** `terraform apply` writes `inventory/hosts.yml`. Without Terraform, copy
`inventory/hosts.example.yml` to `hosts.yml` and fill it in: the `control_plane` and `workers`
groups, each host's `ansible_host` and `node_ip`, and the addresses under `all.vars`.

**Outbound HTTPS from the nodes** to the Ubuntu archive, `pkgs.k8s.io` (served from
`prod-cdn.packages.k8s.io`) and `github.com` (served from `release-assets.githubusercontent.com`).
No route to the internet? See [Mirrors](#mirrors-and-networks-without-internet-access).

---

## Variables

| To change | Edit |
|---|---|
| A component's version | `group_vars/all/versions.yml` |
| Cluster name, DNS domain, pod or service network | `group_vars/all/main.yml` |
| Node addresses, the API VIP, the MetalLB pool | `terraform.tfvars`, then `terraform apply` — Terraform rewrites the inventory |
| How a role behaves (NTP servers, registry mirrors, …) | Set the role's variable in `group_vars/all/main.yml` |

Every role variable starts with the role's name and is documented, with its default, in
`roles/<role>/defaults/main.yml`. Addresses are deliberately absent from `group_vars`: a value set
there would silently override the one Terraform checked.

---

## Run

```bash
cd infra/ansible
ansible k8s_cluster -m ansible.builtin.ping
ansible-playbook site.yml --tags nodes
```

The roles are idempotent: a repeat run changes nothing unless a setting did. Useful variations:

| Command | Effect |
|---|---|
| `ansible-playbook site.yml --tags nodes --limit wk-04` | Prepare one new node after Terraform added it |
| `ansible-playbook site.yml --tags nodes --check --diff` | Show what would change. Only meaningful on prepared nodes: on a fresh one, steps that use an earlier download fail in check mode |

---

## What the node layer does

### `node_baseline`

| Step | Why |
|---|---|
| Waits for cloud-init to finish | A freshly cloned VM may still hold the apt lock |
| Sets the host name to the inventory name | The kubelet registers the node under it |
| Installs `curl`, `gpg`, `jq`, `tcpdump`, `conntrack`, `ethtool`, … | Fetching keys and debugging a node or the pod network. kubeadm itself only needs `losetup`, `mount` and `cp`, which the image has |
| Turns swap off, now and in `/etc/fstab` | The kubelet's default (`failSwapOn: true`) refuses to start with swap on |
| Loads `overlay` and `br_netfilter`, now and at boot | containerd's snapshotter; bridged traffic visible to netfilter |
| Sets `ip_forward`, `bridge-nf-call-*` and inotify limits | Pod routing (kubeadm checks `ip_forward`); log shippers watch thousands of files |
| Keeps systemd-timesyncd running | etcd and certificate checks break on drifting clocks |

Set `node_baseline_ntp_servers` to use your own time servers instead of Ubuntu's.

### `containerd`

containerd and runc are installed from their upstream releases, not from apt: Ubuntu's archive
trails upstream (containerd 2.2 in `noble-updates`) and moves on its own schedule, while this way
every node runs exactly the version in `versions.yml`. Each download is verified against a SHA-256
sum pinned next to the version, not one fetched from the same server as the file.

The configuration sets only what the cluster depends on; everything else keeps containerd's
defaults (`sudo containerd config dump` shows the merged result):

| Setting | Value | Why |
|---|---|---|
| `SystemdCgroup` | `true` | systemd owns the cgroup tree on Ubuntu; the kubelet learns this setting through the CRI |
| `pinned_images.sandbox` | `pause_image` | The sandbox image kubeadm expects, pinned so the kubelet never garbage-collects it |
| `registry.config_path` | `/etc/containerd/certs.d` | Per-registry mirrors, below |

Restarting containerd does not stop containers: the unit uses `KillMode=process`, so only the
daemon restarts.

**Upgrading containerd or runc:** set the new version *and* its checksums in `versions.yml`, then
re-run the play. A stale checksum fails the download rather than installing an unverified file.

```bash
C=2.4.3   # new containerd version
R=1.5.3   # new runc version
for arch in amd64 arm64; do
  curl -sL "https://github.com/containerd/containerd/releases/download/v$C/containerd-$C-linux-$arch.tar.gz.sha256sum"
done
curl -sL "https://github.com/opencontainers/runc/releases/download/v$R/runc.sha256sum" | grep -E 'runc\.(amd64|arm64)$'
```

### `kubernetes_packages`

Adds the `pkgs.k8s.io` repository for the configured minor version
(`/etc/apt/sources.list.d/kubernetes.sources`, key in `/etc/apt/keyrings/`), installs kubelet,
kubeadm and kubectl at exactly `kubernetes_version`, holds them there, and adds `cri-tools` for
`crictl`. The kubelet restarts in a loop until kubeadm configures it — that is expected.

**Version guard.** If a node already runs a different kubelet version, the role stops:

```
cp-01 runs kubelet 1.36.5-1.1 but kubernetes_version is 1.36.6. A version change is a kubeadm
upgrade (control planes first), not a re-run of this playbook. ...
```

The kubelet must never be newer than the API server it talks to, and bumping the packages does not
upgrade the control plane — `kubeadm upgrade` does. `kubernetes_packages_allow_version_change=true`
lifts the guard and is meant only for that procedure, which is part of
[10 — Operations](10-operations.md).

---

## Mirrors and networks without internet access

Everything the node layer downloads can come from inside your network.

**Container images** — `containerd_registry_mirrors` writes one `hosts.toml` per registry; mirrors
are tried in order and the registry itself last. `_default` covers every registry without its own
entry:

```yaml
# group_vars/all/main.yml
containerd_registry_mirrors:
  docker.io:
    - url: https://mirror.example.internal
  _default:
    - url: https://harbor.example.internal/v2/proxy-cache
      override_path: true          # the mirror serves the registry under a sub-path
      ca: /usr/local/share/ca-certificates/corp-root.crt
```

containerd reads these files on every pull, so changes apply without a restart. The role owns
`/etc/containerd/certs.d`: a registry removed from the variable is removed from the nodes.

**Binaries and packages** — point the download locations at a mirror that keeps the upstream file
names (a Nexus or Artifactory proxy, or a plain web server):

| Variable | Default |
|---|---|
| `containerd_download_base_url` | `https://github.com/containerd/containerd/releases/download/v<version>` |
| `containerd_runc_download_base_url` | `https://github.com/opencontainers/runc/releases/download/v<version>` |
| `kubernetes_packages_repo_url` | `https://pkgs.k8s.io/core:/stable:/v<minor>/deb/` |

The checksums still apply, so a mirror cannot substitute a different file.

---

## Verify

From the control machine, across every node:

```bash
ansible k8s_cluster -b -m ansible.builtin.command -a 'kubeadm version -o short'   # v1.36.6
ansible k8s_cluster -b -m ansible.builtin.command -a 'apt-mark showhold'         # kubeadm kubectl kubelet
```

On a node:

```bash
swapon --show                                     # no output
sysctl net.ipv4.ip_forward                        # = 1
timedatectl show -p NTPSynchronized               # NTPSynchronized=yes
systemctl is-active containerd                    # active
sudo containerd config dump | grep -E 'SystemdCgroup|sandbox ='
sudo crictl info | jq '.status.conditions[] | {type, status}'
```

`crictl info` reports `RuntimeReady: true` and `NetworkReady: false` — there is no pod network
until Cilium is installed.

---

## Checks before committing

```bash
yamllint -s infra/ansible                                  # from the repository root
cd infra/ansible
ansible-playbook -i inventory/hosts.example.yml site.yml --syntax-check
ansible-lint site.yml
```

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | The node was rebuilt with the same address. `ssh-keygen -R 10.10.0.21`, then re-run |
| `Failed to lock apt for exclusive operation` | Something else held apt for over a minute — usually unattended-upgrades on a first boot. Re-run |
| `The checksum for … did not match` | The version was bumped without its checksums, or a mirror serves a different file |
| `No package matching 'kubelet=…'` | `kubernetes_version` names a release that does not exist, or the repository URL points at a mirror that lacks it |
| The version guard stops the run | A node runs another Kubernetes version than `versions.yml`. See [Version guard](#kubernetes_packages) |
