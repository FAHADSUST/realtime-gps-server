# 07 — GitOps: Argo CD and the app of apps

Ansible builds the cluster up to Argo CD; from there, Git is the source of truth. Argo CD keeps the
observability stack, the image registry and the GPS services exactly as described in
[`deploy/kubernetes/gitops`](../../deploy/kubernetes/gitops): a change is a reviewed commit, and
drift made by hand is undone. Code: role `argocd` in
[`infra/ansible/roles`](../../infra/ansible/roles) and the `gitops` chart.

---

## Who owns what

| Owned by Ansible | Owned by Argo CD |
|---|---|
| Nodes, Kubernetes, Cilium, MetalLB, Longhorn, Ingress-NGINX, cert-manager, Argo CD itself | Prometheus, Alertmanager, Grafana, Loki, Alloy, the image registry, the GPS services |
| Every Secret (from the vault) | Only references to those Secrets, by name |

Argo CD cannot install what it runs on — the network, storage and certificates it needs — so those
stay with Ansible. Everything above them moves to Git, where history, review and rollback come for
free. No secret is ever committed: Ansible writes them into the cluster, and the manifests in Git
only name them.

---

## The app of apps

```
 Ansible ──▶ Application "gitops" (project default)      reads deploy/kubernetes/gitops
                    │                                      at gitops_revision of gitops_repo_url
                    ▼
            AppProject "platform"          wave -1    allowed sources and namespaces
            Application ...                wave 0+    one per component, added layer by layer
```

Ansible creates exactly one Application, `gitops`. It renders the `gitops` Helm chart, whose
templates are Applications — one per component — plus the `platform` AppProject that confines them
to known repositories and namespaces. Cluster-specific settings (domain, issuer, monitoring
namespace, repository) are not in Git: Ansible passes them from the inventory into the root
Application, which hands them to the chart. Re-run `--tags argocd` after changing them.

Every child Application syncs automatically, **prunes** what was removed from Git, **self-heals**
manual changes, creates its namespace, uses server-side apply and retries with back-off. Sync
waves order them — observability first, so everything after it is monitored from its first pod —
and the restored Application health check makes each wave wait until the previous one is healthy,
not merely created.

---

## Access

| | |
|---|---|
| UI | `https://argocd.rls.internal`, user `admin` |
| Password | The one you hashed into `vault_argocd_admin_password_bcrypt` |
| CLI | `argocd login argocd.rls.internal --grpc-web --username admin` (the cluster CA must be trusted, see [06 — TLS](06-tls.md)) |

To change the password, put a new hash in the vault and re-run `--tags argocd`. Single sign-on
(Dex or OIDC) and notifications are disabled until configured.

**Private or internal repositories.** Set `gitops_repo_url` (and `gitops_revision`) in
`group_vars/all/main.yml` — for a cluster without internet access, an internal Git server holding
a mirror of this repository. If it needs credentials, add `vault_gitops_repo_username` and
`vault_gitops_repo_password` (a read-only token) to the vault; Ansible creates the repository
Secret.

---

## Making a change

1. Change the chart's values or templates under `deploy/kubernetes/gitops` in a branch.
2. Review: `helm template deploy/kubernetes/gitops` shows exactly what Argo CD will apply.
3. Merge into `gitops_revision`. Argo CD notices within three minutes — **Refresh** in the UI
   makes it immediate — and syncs.

Rolling back is reverting the commit.

---

## Run

```bash
cd infra/ansible
ansible-playbook site.yml --tags argocd --ask-vault-pass
```

The play ends by waiting until Argo CD reports the `gitops` application Synced, which proves it
can read the repository.

## Verify

```bash
kubectl -n argocd get applications                    # gitops: Synced, Healthy
kubectl -n argocd get appproject platform
kubectl -n argocd get pods
```

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| The play times out waiting for `Synced` | Argo CD cannot read `deploy/kubernetes/gitops` at `gitops_revision`: the repository is unreachable from the cluster, private without credentials, or the branch does not contain the chart yet. `kubectl -n argocd describe application gitops` shows the error |
| An application is stuck `OutOfSync` | `argocd app diff <name>` (or the UI's diff view) shows what differs. A field another controller keeps changing needs an `ignoreDifferences` entry |
| A sync wave never proceeds | An application in the previous wave is not Healthy; its resources show which one and why |
| `admin` cannot log in | The vault value must be a bcrypt hash starting with `$2a$` or `$2b$`, not the plain password |
| The Argo CD Helm task fails without details | Its output is hidden because it contains the password hash. On the first control plane: `helm -n argocd status argocd`, and the events in the `argocd` namespace |
