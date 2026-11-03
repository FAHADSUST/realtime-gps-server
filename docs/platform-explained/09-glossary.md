# 9 — Glossary

[← Interview questions](08-interview-questions.md) · Next: [Two or more Proxmox hosts →](10-proxmox-hosts.md)

Every term used in these pages, in plain words. Where it matters, the *here* column says how this
project uses it.

| Term | In plain words | Here |
|---|---|---|
| **ACME** | The protocol Let's Encrypt uses to prove you own a domain before issuing a certificate | Not used: needs the internet |
| **Admission webhook** | A program the API server asks "may this object be created?" before storing it | Ingress-NGINX's is reachable only by the API server |
| **Alertmanager** | Receives alerts from Prometheus, groups them and sends notifications | `alertmanager.rls.internal`; no receivers configured yet |
| **Alloy** | Grafana's collector; here it reads container logs and ships them to Loki | One per node |
| **Ansible** | Configures machines over SSH from playbooks of tasks | Builds the nodes and the cluster |
| **Ansible Vault** | Encrypts a file of variables with a password | Holds every secret |
| **App of apps** | One Argo CD application whose job is to create the other applications | The root `gitops` application |
| **ARP** | How a machine finds which network card owns an IP address on the local network | kube-vip and MetalLB answer ARP for their addresses |
| **Argo CD** | A GitOps tool: makes the cluster match what Git describes, continuously | Deploys monitoring, logging, the registry and the app |
| **bcrypt** | A deliberately slow password-hashing function, so stolen hashes are hard to crack | Passwords, app secrets, the registry's htpasswd |
| **BGP** | The routing protocol of the internet; MetalLB can use it to spread traffic | Not used — Layer 2 mode instead |
| **Bootstrap token** | A short-lived credential a new node uses to join the cluster | One per node, valid 15 minutes |
| **CA (certificate authority)** | Signs certificates; whoever trusts the CA trusts what it signs | `rls-onprem root CA`, inside cert-manager |
| **cert-manager** | Issues and renews TLS certificates inside Kubernetes | ClusterIssuer `rls-ca` |
| **cgroup driver** | How Linux limits CPU and memory for processes; the kubelet and runtime must agree on it | systemd |
| **Cilium** | Networking for Kubernetes built on eBPF | CNI, kube-proxy replacement, network policies, Hubble |
| **ClusterIssuer** | A cert-manager object describing how to sign certificates, for any namespace | `rls-ca` |
| **CNI** | The plugin standard that gives pods their network | Cilium |
| **containerd** | The container runtime: it pulls images and runs containers for the kubelet | 2.4.2, from upstream |
| **Control plane** | The nodes running Kubernetes' brain: API server, etcd, scheduler, controller manager | cp-01, cp-02, cp-03 |
| **Cordon / drain** | Mark a node "no new pods" / then move its pods elsewhere — before maintenance | Every reboot and upgrade |
| **Corosync** | The heartbeat and voting system of a Proxmox cluster; it needs low, steady latency | On a dedicated link between the hosts |
| **Correlation id** | An id attached to a request and written in every log line it causes | `X-Correlation-Id`, set by Kong |
| **CRD** | Custom Resource Definition: teaches Kubernetes a new kind of object | `ServiceMonitor`, `Certificate`, `Application`… |
| **CSI** | The plugin standard for storage in Kubernetes | Longhorn's driver |
| **DaemonSet** | Runs one copy of a pod on every (matching) node | Cilium, Alloy, Ingress-NGINX |
| **Dead-letter queue** | Where messages go when they can't be processed, so they aren't lost or retried forever | `gps.location.history.dlq` |
| **Deployment** | Runs N identical, interchangeable pods and replaces them during updates | The GPS services, Kong |
| **Drift** | The cluster differing from what Git says, for example after a manual change | Argo CD reverts it |
| **eBPF** | Small, safe programs loaded into the Linux kernel, used for fast networking | Cilium's datapath |
| **etcd** | The database where Kubernetes stores every object; needs a majority to work | 3 members, one per control plane |
| **externalTrafficPolicy: Local** | Deliver outside traffic only to pods on the receiving node — keeps the client's address | The ingress Service |
| **Gateway API** | The successor to Ingress: richer, role-oriented HTTP routing | The planned replacement for Ingress-NGINX |
| **GitOps** | Running infrastructure from Git: an agent pulls the desired state and reconciles it | Argo CD |
| **Grafana** | Dashboards over metrics and logs | `grafana.rls.internal` |
| **Health check (Argo CD)** | How Argo CD decides a resource is healthy, not just created | Restored for Applications, so sync waves wait |
| **Helm / chart** | Kubernetes' package manager / a package of templated manifests | Most components install as charts |
| **Hubble** | Cilium's flow visibility: shows every connection and why it was allowed or dropped | `hubble.rls.internal` |
| **Idempotent** | Running it again changes nothing if nothing needs changing | Every playbook |
| **Ingress** | A Kubernetes rule: "requests for this host go to that Service" | One per UI and the API |
| **Ingress-NGINX** | The controller that implements Ingress rules with NGINX | The front door, `10.10.0.200` |
| **IPAM** | IP address management: who hands out addresses | Pods: per-node ranges from Kubernetes; `LoadBalancer` addresses: MetalLB; ClusterIPs: the API server |
| **kubeadm** | The official tool that creates and upgrades Kubernetes clusters | `kubeadm init`, `join`, `upgrade` |
| **kubelet** | The agent on every node that starts and watches the pods assigned to it | 1.36.6 |
| **kube-proxy** | The default way Services are implemented, with iptables rules | Disabled — Cilium replaces it |
| **kube-vip** | Gives the control planes one floating IP address | `10.10.0.10` |
| **Kong** | An API gateway: authentication, rate limits and routing in front of services | 3.9.4, with the `rls_auth` plugin |
| **Lease** | A small Kubernetes object used for leader election: whoever renews it is the leader | kube-vip's leader election |
| **Live migration** | Moving a running VM to another host without stopping it — here with its local disks too | Patching a Proxmox host |
| **LoadBalancer Service** | A Service with an address reachable from outside the cluster | Given addresses by MetalLB |
| **Longhorn** | Replicated block storage made from the nodes' own disks | 3 replicas per volume |
| **Loki** | Stores logs, indexed only by labels | `monitoring` namespace, 14 days |
| **MetalLB** | Provides `LoadBalancer` addresses on bare metal and VMs | Layer 2 mode, `10.10.0.200–.230` |
| **Namespace** | A folder for Kubernetes objects; policies and permissions apply per namespace | `gps`, `monitoring`, `argocd`… |
| **Network policy** | Firewall rules between pods | Default deny in `gps` |
| **Node** | One machine in the cluster | 6 VMs |
| **PersistentVolumeClaim (PVC)** | A pod's request for storage; Kubernetes finds or creates a volume for it | Served by Longhorn |
| **Pod** | The smallest unit Kubernetes runs: one or more containers sharing a network address | |
| **PodDisruptionBudget** | "Never voluntarily take down more than N of these pods" — respected by drains | At least 1 per service |
| **Probe** (startup, readiness, liveness) | Kubernetes' checks: has it started? may it get traffic? should it be restarted? | On every service |
| **Prometheus** | Collects metrics by scraping endpoints, stores them, evaluates alert rules | 15 days of metrics |
| **PromQL / LogQL** | The query languages of Prometheus and Loki | Alerts, dashboards, log searches |
| **Proxmox VE** | An open-source hypervisor for running VMs | Version 9; runs the 6 VMs |
| **Proxmox cluster** | Proxmox hosts joined into one: shared configuration, one API, VMs that can move between hosts | Two or more hosts ([page 10](10-proxmox-hosts.md)) |
| **QDevice** | A small daemon on a third machine that gives a two-host Proxmox cluster its third vote | Two-host clusters only |
| **Quorum** | A majority of members — what etcd and a Proxmox cluster need before they act | 2 of 3 |
| **RabbitMQ** | A message broker: queues messages between services | Ping → history pipeline |
| **RBAC** | Role-based access control: who may do what in the Kubernetes API | |
| **Reconcile** | Compare desired with actual state and fix the difference — the core loop of Kubernetes and Argo CD | |
| **Redis** | An in-memory data store; here also a geo index | Latest location per user |
| **Rolling update** | Replace pods a few at a time, so the service never goes down | Every release |
| **Secret** | A Kubernetes object holding sensitive values | Written by Ansible from the vault |
| **Self-heal** | Argo CD undoing changes made outside Git | On for every application |
| **Server-side apply** | The API server merges your manifest and tracks who owns each field | Ansible and Argo CD both use it |
| **ServiceMonitor** | Tells Prometheus which Service to scrape and how | Shipped with every chart |
| **SLO / error budget** | A reliability target (e.g. 99.9 %) / how much failure that target allows | |
| **Split brain** | Two halves of a cluster each carrying on alone after losing contact — what a majority rule prevents | Why two hosts need a QDevice |
| **Static pod** | A pod the kubelet runs from a file on disk, with no API server needed | The control plane components, kube-vip |
| **StatefulSet** | Like a Deployment, but pods keep a stable name and their own volume | MySQL, Redis, RabbitMQ |
| **StorageClass** | Describes a kind of storage that volumes can be created from | `longhorn` (default) |
| **Sync wave** | An Argo CD annotation that orders what gets applied first | 0 → 3 |
| **Terraform** | Declares infrastructure as code and creates it through APIs | The 6 VMs |
| **Terraform state** | Terraform's record of what it created | `terraform.tfstate` |
| **TLS termination** | Where HTTPS is decrypted | At Ingress-NGINX |
| **VIP (virtual IP)** | An address not tied to one machine; it moves to whichever node holds it | The API's `10.10.0.10` |
| **VXLAN** | Wrapping packets inside UDP to cross networks ("tunnelling") | Not needed — native routing |
| **Worker** | A node that runs the applications | wk-01, wk-02, wk-03 |

---

[← Interview questions](08-interview-questions.md) · Next: [Two or more Proxmox hosts →](10-proxmox-hosts.md)
