# 08 — Observability: Prometheus, Grafana, Loki and Alloy

Metrics, dashboards and logs for the cluster, the platform and the GPS services. Everything here is
deployed by Argo CD from [`deploy/kubernetes/gitops`](../../deploy/kubernetes/gitops) into the
`monitoring` namespace, in the first sync wave, so whatever comes after it is watched from its
first pod.

| Component | Does | URL |
|---|---|---|
| Prometheus | Scrapes and stores metrics, evaluates alert rules | `https://prometheus.rls.internal` |
| Alertmanager | Groups, silences and routes alerts | `https://alertmanager.rls.internal` |
| Grafana | Dashboards over metrics and logs | `https://grafana.rls.internal` |

Prometheus and Alertmanager have no login of their own and sit behind the operator basic
authentication (see [06 — TLS](06-tls.md#operator-uis)); Grafana has its own.

---

## Prometheus and Alertmanager

kube-prometheus-stack 92.17.0 (Prometheus Operator v0.94.2) provides Prometheus, Alertmanager,
node-exporter on every node, kube-state-metrics and the Kubernetes community's alert rules.
Static values: [`values/kube-prometheus-stack.yaml`](../../deploy/kubernetes/gitops/values/kube-prometheus-stack.yaml);
host names and labels per cluster: `templates/kube-prometheus-stack.yaml`.

**What is scraped.** Prometheus selects every `ServiceMonitor`, `PodMonitor`, `PrometheusRule`,
`Probe` and `ScrapeConfig` in the cluster, not only its own release's. Each layer before it shipped
monitors and rules of its own, so on its first start Prometheus already covers:

| Source | Through |
|---|---|
| API server, kubelets and cAdvisor, CoreDNS | kube-prometheus-stack |
| Controller manager, scheduler, etcd | kube-prometheus-stack; reachable because kubeadm configured them to listen beyond localhost |
| Nodes, Kubernetes objects | node-exporter, kube-state-metrics |
| Cilium agent, operator, Envoy, Hubble flows | Cilium's ServiceMonitors |
| MetalLB (with pool-usage alerts), Longhorn, Ingress-NGINX, cert-manager, Argo CD | Their charts' ServiceMonitors and rules |

Add-ons that restrict traffic to their pods admit exactly one scraper: pods labelled
`app.kubernetes.io/name: prometheus` in the `monitoring` namespace, which is what the operator
labels Prometheus with.

**Choices.**

| Setting | Value | Why |
|---|---|---|
| CRDs | Not installed by the chart | Ansible installed them earlier, at the same operator version, so every layer could ship monitors |
| kube-proxy monitoring and rules | Off | Cilium replaces kube-proxy |
| Grafana | Not part of this release | Deployed on its own from the grafana-community chart |
| Webhook certificates | From cert-manager | The chart's certificate jobs would run, and change the webhooks, on every Argo CD sync |
| Prometheus | One replica, 30 Gi Longhorn volume, 15 days or 25 GB of data | The volume is replicated three times; if a node fails, Prometheus restarts elsewhere with its data. A second replica would double the storage for the same metrics |
| Alertmanager | One replica, 2 Gi | Silences and notification state survive restarts |
| External labels | `cluster: rls-onprem` | Identifies the source if metrics are ever federated or forwarded |

**Alert routing.** Out of the box, alerts are visible in Alertmanager and Prometheus but sent
nowhere: Alertmanager has no receivers yet. To send them to e-mail, Slack, Teams or a webhook, add
an `alertmanager.config` block to the values file, with the credentials in a Secret written by
Ansible from the vault and referenced by name — never in Git. The `Watchdog` alert fires
permanently on purpose: it proves the alerting pipeline works end to end.

---

## Grafana

Grafana 13.3.0 from the grafana-community chart 14.3.0 — Grafana Labs deprecated its own
`grafana/grafana` chart, and the community now maintains it. Values:
[`values/grafana.yaml`](../../deploy/kubernetes/gitops/values/grafana.yaml). It syncs in wave 1,
after the data it shows exists.

| Setting | Value | Why |
|---|---|---|
| Admin login | User `admin`, password from the vault (`vault_grafana_admin_password`) | Ansible writes the `grafana-admin` Secret; Git only names it |
| Data sources | Loaded from ConfigMaps labelled `grafana_datasource` | kube-prometheus-stack publishes Prometheus (the default) and Alertmanager with their in-cluster addresses |
| Dashboards | Loaded from ConfigMaps labelled `grafana_dashboard`, in any namespace | Kubernetes, node and Prometheus dashboards from kube-prometheus-stack; Cilium, Hubble and Cilium operator dashboards from Cilium |
| Storage | 2 Gi Longhorn volume, `Recreate` updates | Users, preferences and annotations survive restarts; dashboards are re-provisioned on every start anyway |
| Phoning home | Off | No update checks, usage reports, news feed or Gravatar |

Provisioned dashboards cannot be edited in place. To keep a dashboard of your own, export its JSON
into a ConfigMap labelled `grafana_dashboard: "1"`, committed to Git — or save a copy under another
name, which lives in Grafana's database.

---

## Run

Nothing to run by hand: once Argo CD has synced the `gitops` application, it creates
`kube-prometheus-stack` and `grafana` and keeps them in line with Git. The Grafana admin Secret
comes first, from Ansible: `ansible-playbook site.yml --tags secrets --ask-vault-pass`. To change a setting, edit the values file and
merge (see [07 — GitOps](07-gitops.md#making-a-change)).

## Verify

```bash
kubectl -n argocd get application kube-prometheus-stack     # Synced, Healthy
kubectl -n monitoring get pods
kubectl -n monitoring get prometheus,alertmanager
```

In `https://prometheus.rls.internal`, **Status → Target health** lists every target `UP`, including
`kube-controller-manager`, `kube-scheduler` and `kube-etcd`. **Alerts** shows `Watchdog` firing and,
on a healthy cluster, nothing else.

In `https://grafana.rls.internal`, sign in as `admin`. **Connections → Data sources** lists
Prometheus and Alertmanager; **Dashboards** holds the Kubernetes and Cilium folders, with data.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| A target is `DOWN` with `connection refused` | The component listens on localhost only. For the control plane, this cluster sets the bind addresses at `kubeadm init`; on a cluster built without them, see [03 — Kubernetes](03-kubernetes.md#cluster-configuration) |
| A target is `DOWN` with a timeout | A network policy blocks Prometheus. Policies must admit pods labelled `app.kubernetes.io/name: prometheus` from `monitoring`; `hubble observe --namespace <ns> --verdict DROPPED` shows the drop |
| A ServiceMonitor exists but no target appears | Its selector does not match the Service's labels, or the Service's port name differs from the one the monitor names |
| The application is `OutOfSync` on webhook configurations | The `caBundle` difference is ignored by design; anything else in the diff is real |
| Grafana's pod fails with `secret "grafana-admin" not found` | The secrets layer has not run: `ansible-playbook site.yml --tags secrets --ask-vault-pass` |
| A dashboard shows "No data" | Check its data source variable at the top of the dashboard, then the query's metric in Prometheus: the target behind it may be down |
