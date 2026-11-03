# 1 — The big picture

[← Start](README.md) · Next: [The twelve layers →](02-the-twelve-layers.md)

---

## The problem

The Realtime GPS Server collects location "pings" from thousands of devices, keeps every user's
latest position for "who is near me?" queries, and stores the full history. It is a set of Spring
Boot microservices behind the Kong API gateway, with MySQL, Redis and RabbitMQ.

```mermaid
flowchart TB
    devices(["Phones and trackers"]) -->|"location pings<br/>and queries"| kong["Kong<br/>API gateway"]
    kong --> id["id-service<br/>companies, users, tokens"]
    kong --> ping["ping-service<br/>receives every location"]
    ping -->|"latest position"| redis[("Redis")]
    ping -->|"batches"| mq[["RabbitMQ"]]
    mq --> history["history-service<br/>stores the history"]
    id --> mysql[("MySQL")]
    history --> mysql
```

On a laptop this runs with Docker Compose. **The goal of this platform:** run it in production on
hardware the organisation owns — no cloud provider — with the reliability, security and automation
a cloud would give.

---

## The idea: infrastructure as code, all the way down

Every layer, from the virtual machines to the application, is described in this Git repository and
built by a tool. Nothing is configured by hand, so the whole platform can be rebuilt from scratch and
every change is a reviewed commit.

```mermaid
flowchart TB
    L6["6 - Application<br/>GPS services, Kong, MySQL, Redis, RabbitMQ"]
    L5["5 - Observability<br/>Prometheus, Grafana, Loki"]
    L4["4 - Platform services<br/>MetalLB, Longhorn, Ingress-NGINX, cert-manager, Argo CD"]
    L3["3 - Kubernetes<br/>kubeadm, kube-vip, Cilium"]
    L2["2 - Operating system<br/>Ubuntu 24.04, containerd"]
    L1["1 - Virtual machines<br/>6 VMs on Proxmox VE"]
    L6 --- L5 --- L4 --- L3 --- L2 --- L1

    T1(["Terraform"]) -.-> L1
    T2(["Ansible"]) -.-> L2
    T2 -.-> L3
    T2 -.-> L4
    T3(["Argo CD, from Git"]) -.-> L5
    T3 -.-> L6
```

> [!NOTE]
> **One sentence to remember:** *Terraform builds the machines, Ansible builds the cluster, Git
> (through Argo CD) runs everything on top.*

---

## The pipeline, in order

The twelve tools, grouped by what they achieve. Each one stands on the previous ones.

```mermaid
flowchart TB
    subgraph p1["1 - Provision"]
        A["Terraform"]
    end
    subgraph p2["2 - Configure"]
        B["Ansible"]
    end
    subgraph p3["3 - Cluster"]
        direction LR
        C["kubeadm"] --> D["Cilium"]
    end
    subgraph p4["4 - Platform"]
        direction LR
        E["MetalLB"] --> F["Longhorn"] --> G["Ingress-NGINX"] --> H["cert-manager"]
    end
    subgraph p5["5 - GitOps"]
        I["Argo CD"]
    end
    subgraph p6["6 - Observe"]
        direction LR
        J["Prometheus"] --> K["Grafana"] --> L["Loki"]
    end
    p1 --> p2 --> p3 --> p4 --> p5 --> p6
```

| Phase | Tools | What you have afterwards |
|---|---|---|
| Provision | Terraform | Six Ubuntu VMs with fixed addresses, and an inventory file listing them |
| Configure | Ansible | Nodes ready for Kubernetes: swap off, kernel settings, container runtime, Kubernetes packages |
| Cluster | kubeadm, Cilium | A working, highly available cluster with a pod network |
| Platform | MetalLB, Longhorn, Ingress-NGINX, cert-manager | Services reachable from outside, durable storage, HTTPS for every name |
| GitOps | Argo CD | A cluster that keeps itself in line with Git |
| Observe | Prometheus, Grafana, Loki | Metrics, dashboards, alerts and searchable logs |

---

## The physical view

Six virtual machines on Proxmox VE. Control planes run Kubernetes itself; workers run the
applications and hold the storage.

```mermaid
flowchart TB
    subgraph pve["Proxmox VE host"]
        subgraph cps["Control planes - 4 vCPU, 8 GiB, 60 GiB disk"]
            cp1["cp-01<br/>10.10.0.11"]
            cp2["cp-02<br/>10.10.0.12"]
            cp3["cp-03<br/>10.10.0.13"]
        end
        subgraph wks["Workers - 8 vCPU, 16 GiB, 80 GiB disk + 200 GiB data disk"]
            wk1["wk-01<br/>10.10.0.21"]
            wk2["wk-02<br/>10.10.0.22"]
            wk3["wk-03<br/>10.10.0.23"]
        end
    end
    vip(("API virtual IP<br/>10.10.0.10")) -.->|"held by one<br/>control plane"| cps
    ingress(("Ingress IP<br/>10.10.0.200")) -.->|"answered by<br/>one worker"| wks
```

**Why three control planes?** Kubernetes stores its state in **etcd**, which needs a majority
(*quorum*) to work. With three members it survives losing one. Two would survive nothing; four
would survive the same one failure as three.

The diagram shows one Proxmox host for simplicity — but then that host is a single point of
failure. Spreading the VMs over two or more hosts, and what each layout protects, is
[page 10](10-proxmox-hosts.md).

---

## The logical view: what runs inside the cluster

Kubernetes groups workloads into **namespaces**. Each platform tool lives in its own.

```mermaid
flowchart TB
    subgraph ks["kube-system"]
        direction TB
        api["API server, etcd,<br/>scheduler, controller manager"]
        vip["kube-vip"]
        cil["Cilium + Hubble"]
        dns["CoreDNS"]
    end
    subgraph platform["One namespace per platform component"]
        direction TB
        mlb["metallb-system<br/>MetalLB"]
        ing["ingress-nginx<br/>Ingress-NGINX"]
        lh["longhorn-system<br/>Longhorn"]
        cm["cert-manager<br/>certificates, cluster CA"]
    end
    subgraph gitops["argocd"]
        acd["Argo CD"]
    end
    subgraph deployed["Deployed by Argo CD"]
        direction TB
        subgraph mon["monitoring"]
            direction LR
            prom["Prometheus,<br/>Alertmanager"]
            graf["Grafana"]
            loki["Loki, Alloy"]
        end
        subgraph reg["registry"]
            r["Image registry"]
        end
        subgraph gps["gps"]
            direction LR
            k["Kong"]
            svc["id, ping, history<br/>services"]
            data["MySQL, Redis,<br/>RabbitMQ"]
        end
    end
    ks ~~~ platform ~~~ gitops
    acd -->|"deploys"| deployed
```

---

## The address plan

Everything sits on one network, `10.10.0.0/24`, and every address is set in Terraform. It refuses a
plan in which two nodes share an address, or the API's or the ingress's address is also given to a
node.

```mermaid
flowchart LR
    subgraph net["Node network 10.10.0.0/24"]
        direction LR
        a1["10.10.0.10<br/>Kubernetes API - virtual IP"]
        a2["10.10.0.11 - .13<br/>control planes"]
        a3["10.10.0.21 - .23<br/>workers"]
        a4["10.10.0.200<br/>ingress - reserved"]
        a5["10.10.0.201 - .230<br/>other LoadBalancer services"]
    end
    subgraph inside["Inside the cluster only"]
        direction LR
        b1["10.244.0.0/16<br/>pods"]
        b2["10.96.0.0/12<br/>services"]
    end
    net ~~~ inside
```

| DNS name | Points to | Serves |
|---|---|---|
| `*.rls.internal` | `10.10.0.200` | every web UI and the API, through Ingress-NGINX |
| `k8s-api.rls.internal` | `10.10.0.10` | the Kubernetes API (optional) |

Names behind the wildcard: `api` (the GPS API), `argocd`, `grafana`, `prometheus`,
`alertmanager`, `hubble`, `longhorn`, `registry`.

---

## Who installs what

The most important design decision: **a clear hand-over from Ansible to Argo CD.**

```mermaid
flowchart LR
    subgraph ansible["Installed by Ansible"]
        direction LR
        n1["Nodes and container runtime"]
        n2["Kubernetes - kubeadm, kube-vip"]
        n3["Cilium, MetalLB, Longhorn"]
        n4["Ingress-NGINX, cert-manager"]
        n5["Every Secret, from the vault"]
        n6["Argo CD itself"]
    end
    subgraph argo["Deployed by Argo CD, from Git"]
        direction LR
        m1["Prometheus, Alertmanager"]
        m2["Grafana"]
        m3["Loki, Alloy"]
        m4["Image registry"]
        m5["GPS services, Kong, data stores"]
    end
    ansible ==>|"hands over"| argo
```

**Why split it?** It's a design choice, made for two reasons. Some layers need work on the nodes
themselves — preparing Longhorn's disks, trusting the cluster CA — which is Ansible's job anyway.
And the rest are what Argo CD itself runs on and is reached through: the pod network, the ingress,
the certificates. Kept out of Argo CD, a bad sync can never break what Argo CD would need to repair
it. (Many teams do let Argo CD manage MetalLB or cert-manager; it works, with that risk.) Everything
above lives in Git, where it gains review, history and rollback. Secrets are always written by
Ansible — Git only refers to them by name.

---

## Key decisions at a glance

| Decision | Why | Alternative considered |
|---|---|---|
| Proxmox VE + Terraform | The most common open-source hypervisor; only one Terraform module is Proxmox-specific | vSphere, bare metal — swap the module, keep the rest |
| kubeadm, three control planes | Upstream Kubernetes, no vendor layer; three survive one failure | k3s, RKE2 — simpler, but less like the Kubernetes interviewers ask about |
| kube-vip for the API address | The API needs a stable address *before* the cluster exists | An external load balancer (HAProxy + keepalived) — more machines |
| Cilium without kube-proxy | eBPF networking: faster, one component, built-in flow visibility | Calico, Flannel + kube-proxy |
| MetalLB in Layer 2 mode | Works on any flat network, no router changes | BGP mode — needs routers that speak BGP |
| Longhorn | Replicated block storage on the workers' own disks | Ceph/Rook — far more to operate |
| Internal CA with cert-manager | No internet needed for certificates | Let's Encrypt — needs public DNS or reachability |
| Argo CD, app of apps | Git is the truth; drift is corrected automatically | Flux; or Helm from a pipeline — no drift correction |
| Every version pinned, node downloads checksummed | Reproducible builds, supply-chain safety | "latest" — unpredictable |

---

[← Start](README.md) · Next: [The twelve layers →](02-the-twelve-layers.md)
