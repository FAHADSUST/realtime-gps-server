# 3 — How it all flows

[← The twelve layers](02-the-twelve-layers.md) · Next: [Security →](04-security.md)

Pages 1 and 2 show the parts. This page shows them working together: the "what happens when…?"
stories interviewers ask you to walk through. Each has one diagram to draw and a few points to say.

| # | Story | What it shows you understand |
|---|---|---|
| 1 | [Building the platform from nothing](#1-building-the-platform-from-nothing) | The order of the layers, and why |
| 2 | [A node joins the cluster](#2-a-node-joins-the-cluster) | How Kubernetes and a new machine learn to trust each other |
| 3 | [A location ping, end to end](#3-a-location-ping-end-to-end) | One request through every layer, from DNS to the database |
| 4 | [Who is near me?](#4-who-is-near-me) | Reading from Redis's geo index, per tenant |
| 5 | [Signing up and getting a token](#5-signing-up-and-getting-a-token) | Authentication at the gateway |
| 6 | [Releasing a new version](#6-releasing-a-new-version) | GitOps: a release is a commit, a rollback is a revert |
| 7 | [A certificate is issued and renewed](#7-a-certificate-is-issued-and-renewed) | HTTPS with no manual steps |
| 8 | [Metrics and logs](#8-metrics-and-logs) | How you find out something is wrong, and why |
| 9 | [A secret, from the vault to a pod](#9-a-secret-from-the-vault-to-a-pod) | Secrets that never touch Git |

---

## 1. Building the platform from nothing

Two commands build the platform — `terraform apply` and `ansible-playbook site.yml` — and from then
on, every change is a `git push`. The application itself arrives with its first release
([story 6](#6-releasing-a-new-version)).

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    actor Op as Operator
    participant TF as Terraform
    participant AN as Ansible
    participant K8S as Cluster
    participant ARGO as Argo CD

    Op->>TF: terraform apply
    TF->>TF: create 6 VMs<br/>on Proxmox
    TF-->>AN: inventory/hosts.yml
    Op->>AN: ansible-playbook site.yml
    AN->>AN: prepare every node
    AN->>K8S: kubeadm init on cp-01
    AN->>K8S: join 2 control planes,<br/>then 3 workers
    AN->>K8S: CRDs, Cilium,<br/>MetalLB, Longhorn
    AN->>K8S: Ingress-NGINX,<br/>cert-manager, cluster CA
    AN->>K8S: Secrets from the vault
    AN->>ARGO: install Argo CD and<br/>the root Application
    ARGO->>ARGO: read the gitops<br/>chart from Git
    ARGO->>K8S: wave 0 - Prometheus, Loki
    ARGO->>K8S: wave 1 - Grafana, Alloy
    ARGO->>K8S: wave 2 - registry
    Note over K8S,ARGO: wave 3 - the GPS application -<br/>switched on with its first release
```

**Why this order?** Every step needs the one before it:

| Step | Must come after… | Because |
|---|---|---|
| Nodes join | kubeadm init | They join an API that must already exist, at the VIP |
| Cilium | the nodes | Nodes report `NotReady` and no pod starts until there is a pod network — this is expected |
| Prometheus CRDs | the cluster | Every later chart ships a `ServiceMonitor`, which only exists once its CRD does |
| MetalLB | Cilium | It runs as pods, and hands Ingress-NGINX its address |
| Longhorn | Cilium | Anything with data — Prometheus, Loki, MySQL — needs volumes |
| cert-manager | Ingress-NGINX | Its first certificate is the ingress's default wildcard certificate, created in Ingress-NGINX's namespace |
| Argo CD | all of the above | Its UI is served through the ingress with a certificate, and what it deploys needs volumes. Keeping those layers in Ansible means a bad sync can't break what Argo CD runs on |
| GPS application | everything, and a release | Last wave, so it is monitored and logged from its very first pod. It is switched on with the first release, once its images are in the registry |

---

## 2. A node joins the cluster

A new machine and the cluster have never met. Each must prove itself to the other.

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    participant AN as Ansible
    participant CP1 as cp-01
    participant NEW as New node
    participant API as API at the VIP

    AN->>CP1: create a bootstrap token,<br/>valid 15 minutes
    opt the new node is a control plane
        AN->>CP1: upload the cluster's keys,<br/>encrypted with a one-time key
    end
    AN->>NEW: write the join configuration
    NEW->>API: kubeadm join 10.10.0.10:6443
    API-->>NEW: cluster information,<br/>signed with the token
    NEW->>NEW: check the CA against<br/>the pinned hash
    NEW->>API: kubelet asks for its<br/>certificate, with the token
    API-->>NEW: client certificate issued
    opt control plane
        NEW->>NEW: start etcd, API server,<br/>scheduler, controller manager
    end
    AN->>NEW: delete the join configuration
    opt control plane
        AN->>NEW: add kube-vip, so this node<br/>can hold the VIP too
    end
    Note over NEW: NotReady until Cilium's<br/>agent runs here, then Ready
```

- **The node trusts the cluster** because the CA's public key matches the hash it was given — a
  machine in the middle can't impersonate the API.
- **The cluster trusts the node** because it presented a valid bootstrap token, created for this
  node alone and expiring after 15 minutes.
- **Control planes also need the cluster's private keys.** They travel encrypted, in a Secret that
  kubeadm deletes after two hours.

---

## 3. A location ping, end to end

The story to tell when asked *"walk me through a request"*. Three parts: the way in, the
synchronous work, and what happens after the device already has its answer.

### The way in

```mermaid
flowchart TB
    dev(["Device"]) -->|"1. look up api.rls.internal"| dns[("DNS<br/>*.rls.internal is 10.10.0.200")]
    dev -->|"2. HTTPS to 10.10.0.200"| arp{{"MetalLB:<br/>wk-02 answers for .200"}}
    arp --> ing["3. Ingress-NGINX on wk-02<br/>TLS ends here"]
    ing -->|"4. network policy: only<br/>Ingress-NGINX may reach Kong"| kong["5. Kong<br/>the API gateway"]
    kong --> ping["6. ping-service"]
```

### The synchronous part — a few milliseconds

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    participant D as Device
    participant K as Kong
    participant ID as id-service
    participant P as ping-service
    participant R as Redis

    D->>K: POST /api/v1/locations<br/>Bearer token, 3 fixes
    Note over D,K: through MetalLB and Ingress-NGINX
    K->>K: rate limit - 600 a minute<br/>per client, per Kong replica
    K->>K: delete identity headers<br/>the client sent
    alt verdict for this token cached
        K->>K: reuse it - at most 60 s old
    else not cached
        K->>ID: GET /api/v1/internal/authenticate<br/>on port 9081
        ID-->>K: 200 + company id, user id,<br/>token lifetime
    end
    K->>P: request + verified identity<br/>+ gateway token
    P->>P: check the gateway token,<br/>identity and batch
    P->>R: Lua script - store the newest<br/>fix, only if newer
    P->>P: queue all 3 fixes<br/>in the memory buffer
    P-->>D: 202 Accepted<br/>accepted 3, dropped 0
```

### The asynchronous part — after the device has its answer

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    participant P as ping-service
    participant Q as RabbitMQ
    participant H as history-service
    participant M as MySQL

    P->>P: flush thread takes what's<br/>queued - up to 500 fixes
    P->>Q: publish the batch<br/>as one message
    Q-->>P: publisher confirm,<br/>handled asynchronously
    Note over P: if RabbitMQ can't be reached,<br/>the batch is dropped and counted
    Q->>H: deliver from the<br/>durable queue
    H->>H: drop and count fixes<br/>that can never be stored
    H->>M: one multi-row INSERT,<br/>duplicates ignored
    H-->>Q: acknowledge
    alt the database is down
        H->>H: try 3 times in all,<br/>waiting longer each time
        H->>Q: park the batch in the<br/>dead-letter queue, for replay
    end
```

### Why it is built this way

| Design choice | Reason |
|---|---|
| Redis gets only the **newest** fix, RabbitMQ gets **every** fix | The current position is read constantly; the track is written once and read rarely |
| The "is it newer?" check runs **inside Redis**, as a Lua script | Devices send buffered fixes out of order; a read-then-write from Java would race with itself |
| Fixes are **batched**, up to 500 per message | At 1,500 requests a second, per-message overhead would dominate |
| The buffer is **bounded** at 50,000 fixes | An unbounded queue turns a slow broker into an out-of-memory crash; a bounded one sheds load and says so (`dropped` in the response) |
| The history insert is **idempotent** (a unique key on company, user and time) | RabbitMQ delivers *at least once*; a redelivered batch must not create duplicate rows |
| Kong **caches** auth decisions for at most 60 seconds | Otherwise every request would also be a call to id-service |

> [!IMPORTANT]
> **Say this before you're asked:** the `202` comes before the history is safe. Fixes wait in
> ping-service's memory until the flush thread publishes them — normally milliseconds, but the
> buffer can hold up to 50,000 if RabbitMQ is slow. A graceful shutdown (`SIGTERM`) drains it; a
> hard kill loses it. A batch RabbitMQ can't take is dropped and counted, not retried. That is a
> deliberate trade: the next fix from a device is a second away, and confirming every write first
> would cost a broker round trip per request. A payments system would choose differently.

---

## 4. Who is near me?

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    participant D as Device
    participant K as Kong
    participant P as ping-service
    participant R as Redis

    D->>K: GET /api/v1/locations/users<br/>lat, lon, radius 5 km
    K->>P: authenticated as above,<br/>company from the token
    P->>P: radius in km,<br/>refuse over 100 km
    P->>R: GEOSEARCH the company's<br/>geo index, nearest first
    R-->>P: user ids and distances
    P->>R: read each user's<br/>last location
    P->>R: remove ids whose<br/>location expired
    P-->>D: users, nearest first,<br/>with distances
```

- **Tenants can't see each other.** Every Redis key contains the company id —
  `gps:<company>:geo`, `gps:<company>:last:<user>` — and the company comes from the verified token,
  never from the query.
- **Silent devices disappear.** A last location expires after 24 hours. Redis can't expire members of
  a geo index, so the search removes stale ones as it passes them — no clean-up job needed.
- **The radius cap is checked in kilometres**, so `radius=5000000&unit=M` is refused just like
  `radius=5000&unit=KM`.

---

## 5. Signing up and getting a token

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    actor A as Platform admin
    actor U as App user
    participant K as Kong
    participant ID as id-service

    A->>ID: register a company on port 9081,<br/>with the server secret
    ID-->>A: app key + app secret<br/>(the secret is shown once)
    U->>K: POST /api/v1/user/signup<br/>app key and secret,<br/>username, password
    K->>ID: public route -<br/>no user to check yet
    ID-->>U: user created
    U->>K: POST /api/v1/auth/token<br/>app key, username, password
    Note over K: 10 attempts a minute per<br/>client, per Kong replica
    K->>ID: forward
    ID-->>U: signed access token,<br/>valid 1 hour
    U->>K: every later call sends<br/>the token as Bearer
```

- **Company registration has no public route at all.** It is served only on id-service's internal
  port, which only Kong's pods can reach — and Kong has no route to it. An operator uses
  `kubectl port-forward`.
- **Only the hash of the app secret is stored** (bcrypt): a lost secret can't be recovered, not even
  by an administrator.
- **The token endpoint is the password-guessing surface**, so it gets its own, much lower rate
  limit.

---

## 6. Releasing a new version

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant REG as Registry
    participant GIT as Git
    participant ARGO as Argo CD
    participant K8S as Cluster

    Dev->>Dev: publish-images.sh -<br/>build, unit-test, 4 images
    Dev->>REG: push the images,<br/>tagged with the commit
    Dev->>GIT: pull request - new<br/>apps.gps.imageTag
    GIT->>GIT: CI runs<br/>validate-platform.sh
    Dev->>GIT: merge to main
    ARGO->>GIT: sees the new commit
    ARGO->>K8S: apply the difference
    K8S->>REG: pull the new images
    K8S->>K8S: rolling update,<br/>one pod at a time
    ARGO-->>Dev: Synced and Healthy
```

For the very first release, the same pull request also sets `apps.gps.enabled: true` — until then
the application's wave is switched off, because there are no images to run.

**How the rolling update avoids errors** — each service runs two pods, and:

```mermaid
flowchart TB
    a["New pod starts"] --> b["Startup probe: the JVM is up"]
    b --> c["Readiness probe: ready for traffic"]
    c --> d["Added to the Service - gets traffic"]
    d --> e["Old pod removed from the Service"]
    e --> f["preStop: waits 5 s for<br/>requests already on their way"]
    f --> g["Graceful shutdown, then exit"]
```

A `PodDisruptionBudget` keeps at least one pod of each service running during node drains, too.

> [!TIP]
> **Rollback is `git revert`** of the release commit. Argo CD rolls the old images back the same
> way. There is no "deploy" button and no `kubectl` in the release process — Git's history *is* the
> deployment history.

---

## 7. A certificate is issued and renewed

```mermaid
%%{init: {"sequence": {"actorMargin": 30, "width": 110}}}%%
sequenceDiagram
    autonumber
    participant ARGO as Argo CD
    participant ING as Ingress
    participant CM as cert-manager
    participant SEC as TLS Secret
    participant NGX as Ingress-NGINX

    ARGO->>ING: create grafana.rls.internal,<br/>cluster-issuer rls-ca
    CM->>ING: notices the annotation
    CM->>CM: new private key,<br/>signed by the root CA
    CM->>SEC: store certificate and key
    NGX->>SEC: load it
    NGX->>NGX: serve https://<br/>grafana.rls.internal
    loop well before it expires
        CM->>SEC: renew
    end
```

**Who trusts the certificate, and why:**

```mermaid
flowchart LR
    root["Root CA<br/>rls-onprem root CA"] -->|"signs"| cert["grafana.rls.internal<br/>certificate"]
    root -.->|"imported once"| ws["Your workstation's<br/>browser"]
    root -.->|"installed by Ansible"| nodes["Every node -<br/>containerd pulls from<br/>registry.rls.internal"]
    ws -->|"trusts"| cert
    nodes -->|"trusts"| cert
```

---

## 8. Metrics and logs

```mermaid
flowchart TB
    app["Any pod"] -->|"/metrics<br/>scraped every 30 s"| prom[("Prometheus")]
    sm["ServiceMonitor<br/>shipped with each chart"] -.->|"says where to scrape"| prom
    app -->|"stdout, one JSON<br/>object per line"| logs["Log files on the node<br/>/var/log/pods"]
    logs --> alloy["Alloy, on every node"]
    alloy -->|"push"| loki[("Loki")]
    prom -->|"alert rules"| am["Alertmanager"]
    prom --> graf["Grafana"]
    loki --> graf
    am -.->|"no receiver yet -<br/>e-mail, chat or pager"| you(["On-call engineer"])
    graf --> you
```

**A debugging scenario to walk through** — how the signals work together when something breaks:

1. **The alert.** `GpsComponentDown` fires for history-service, then `GpsLocationQueueBacklog`: over
   10,000 messages have been waiting in RabbitMQ for 10 minutes.
2. **The dashboard.** *GPS services* in Grafana shows the queue climbing and history-service storing
   nothing — while ping-service answers normally. Devices are unaffected: their requests go through
   Kong, id-service (and MySQL, for token checks), ping-service and Redis — never through
   history-service. Only the history is falling behind.
3. **The logs.** For that time window in Grafana's Explore view:

   ```text
   {namespace="gps", app="history-service"} | json | log_level="ERROR"
   ```

   shows MySQL refusing history-service's login, so the service fails at start-up.
4. **The cause.** Suppose history-service's database password was rotated in the vault earlier that
   day and the Secret rewritten — but MySQL sets a user's password only when its volume is first
   created, so it still has the old one. The runbook's extra step, an `ALTER USER` in MySQL, was
   missed.
5. **The fix.** Run the `ALTER USER`. history-service starts, works through the backlog, and **no
   location is lost**: the batches waited in RabbitMQ's durable queue — exactly what it is for.

> [!NOTE]
> **Contrast it with MySQL itself going down.** Then devices *do* notice: id-service checks every
> token Kong hasn't cached against MySQL, so within a minute logins and requests start failing. And
> history-service, still running, can't store the batches it receives: after three attempts each
> one goes to the dead-letter queue, to be replayed once MySQL is back.

To follow one request through every GPS service, filter on the correlation id Kong gave it — it is
also returned to the client in the `X-Correlation-Id` header:

```text
{namespace="gps"} | json | correlationId="7f3c9a1e-..."
```

Metrics tell you **that** something is wrong and **where**; logs tell you **why**.

---

## 9. A secret, from the vault to a pod

```mermaid
flowchart TB
    v["vault.yml<br/>encrypted with Ansible Vault<br/>never committed"] -->|"ansible-playbook --ask-vault-pass<br/>decrypted in memory only"| a["Ansible<br/>output hidden with no_log"]
    a -->|"writes"| s1["Secret<br/>gps-secrets"]
    a -->|"writes"| s2["Secret<br/>grafana-admin"]
    a -->|"writes"| s3["Secrets registry-htpasswd<br/>and registry-pull"]
    a -->|"bcrypt hash"| s4["Argo CD<br/>admin password"]
    s1 -->|"environment variables"| p["GPS pods"]
    s2 --> g["Grafana"]
    s3 --> k["Registry, and image pulls"]
    git["Git<br/>Argo CD applications"] -.->|"name the Secrets,<br/>never hold values"| s1
```

- **Git holds the shape, the vault holds the values.** A manifest says "read key `jwt-secret` from
  Secret `gps-secrets`"; only Ansible knows what it is.
- **One password, two forms that always match.** The registry's bcrypt entry and the cluster's pull
  credential are both derived from the same vault value. The bcrypt salt is derived too, so
  re-running Ansible changes nothing unless the password does.
- **Rotating a secret:** change it in the vault, re-run the layer that writes it — `--tags secrets`
  for the application's, `ops-ui` or `argocd` for theirs — and restart what reads it. A MySQL user's
  password also needs an `ALTER USER` in MySQL, which sets passwords only on first start.

---

[← The twelve layers](02-the-twelve-layers.md) · Next: [Security →](04-security.md)
