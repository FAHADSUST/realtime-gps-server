# 06 — TLS: cert-manager, the cluster CA and trust

Every HTTPS endpoint in the cluster gets its certificate from cert-manager, signed by a certificate
authority that lives in the cluster. Nothing depends on the internet: no ACME, no public DNS
challenge. Trust that CA once — on the nodes (done automatically) and on your workstations — and
every `*.rls.internal` page is trusted. Code: roles `cert_manager`, `cluster_ca_trust` and `ops_ui`
in [`infra/ansible/roles`](../../infra/ansible/roles).

```
 ClusterIssuer selfsigned-bootstrap ──signs──▶ Certificate rls-root-ca   (10 years, ECDSA P-256,
                                                     │                    key never rotated)
                                    ClusterIssuer rls-ca ◀── uses its Secret
                                                     │
                       signs every other certificate: Ingress hosts, the *.rls.internal default, ...
```

---

## The pieces

| Piece | What it is |
|---|---|
| cert-manager | v1.21.3; controller, webhook and CA injector with two replicas each and disruption budgets. Its CRDs stay if the release is removed |
| `rls-root-ca` | The root CA: a Certificate in the `cert-manager` namespace whose Secret holds the CA key. Valid 10 years; renewal re-signs the same key, so existing trust carries over |
| `rls-ca` | The ClusterIssuer every certificate request names |
| `ingress-nginx/default-tls` | A `*.rls.internal` certificate Ingress-NGINX presents by default, renewed by cert-manager every two months |
| Node trust | Each node has the CA in `/usr/local/share/ca-certificates/`; containerd is restarted to load it, so pulls from in-cluster registries are trusted |
| `artifacts/rls-onprem-root-ca.crt` | A copy of the CA certificate on the control machine, for workstations |

Only the CA's public certificate ever leaves the cluster. Its private key exists in one place: the
`rls-root-ca` Secret. Back it up with the cluster (see [10 — Operations](10-operations.md)) — a lost
key means a new CA, re-trusted everywhere.

---

## Certificates for an Ingress

Either name the issuer, and cert-manager creates and renews a certificate for exactly the listed
hosts:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: rls-ca
spec:
  tls:
    - hosts: [grafana.rls.internal]
      secretName: grafana-tls
```

or list the host under `tls` without a `secretName`, and Ingress-NGINX serves the wildcard default
certificate. The first is preferred for anything long-lived: each host gets its own key.

---

## Trusting the CA on a workstation

The certificate is at `infra/ansible/artifacts/rls-onprem-root-ca.crt` after a run, or:

```bash
kubectl -n cert-manager get secret rls-root-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > rls-onprem-root-ca.crt
```

| System | Import |
|---|---|
| Windows | `Import-Certificate -FilePath rls-onprem-root-ca.crt -CertStoreLocation Cert:\LocalMachine\Root` (administrator PowerShell) |
| macOS | `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain rls-onprem-root-ca.crt` |
| Ubuntu, Debian | `sudo cp rls-onprem-root-ca.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates` |
| Firefox | Settings → Privacy & Security → Certificates → View Certificates → Authorities → Import |
| Java clients | `keytool -importcert -cacerts -alias rls-onprem -file rls-onprem-root-ca.crt` |

Organisations that manage devices centrally distribute it the same way as their own root CAs.

---

## Using an existing enterprise CA

Instead of a self-signed root, cert-manager can sign with an intermediate CA issued by your
organisation's PKI — then every managed device trusts the cluster already. Before running the
`tls` layer:

```bash
kubectl create namespace cert-manager
kubectl -n cert-manager create secret generic rls-root-ca \
  --from-file=tls.crt=intermediate.crt --from-file=tls.key=intermediate.key --from-file=ca.crt=root.crt
```

and set `cert_manager_self_signed_ca: false` in `group_vars/all/main.yml`. The role then checks the
Secret exists and only creates the `rls-ca` issuer on top of it.

---

## Operator UIs

UIs without a login of their own are published behind basic authentication, with certificates from
`rls-ca`:

| URL | UI |
|---|---|
| `https://hubble.rls.internal` | Cilium Hubble: live network flows |
| `https://longhorn.rls.internal` | Longhorn: volumes, replicas, backups |
| `https://prometheus.rls.internal`, `https://alertmanager.rls.internal` | Metrics and alerts, published by Argo CD (see [08 — Observability](08-observability.md)) |

The users and password hashes come from `vault_ops_ui_htpasswd` in the vault (see
[02 — Ansible](02-ansible.md#secrets)); the role refuses to run while it still holds the example
placeholder. Ansible writes them into every namespace with such a UI — including `monitoring`,
whose Ingresses come from Argo CD and only reference the Secret by name. To add or change a user,
edit the vault and re-run `--tags ops-ui`.

---

## Run

```bash
cd infra/ansible
ansible-playbook site.yml --tags tls,ops-ui --ask-vault-pass
```

## Verify

```bash
kubectl get clusterissuers                                  # rls-ca and selfsigned-bootstrap: READY True
kubectl -n cert-manager get certificate rls-root-ca         # READY True
kubectl -n ingress-nginx get certificate default-tls        # READY True
kubectl get certificates -A                                 # hubble-tls, longhorn-tls: READY True
curl --cacert artifacts/rls-onprem-root-ca.crt -u ops https://hubble.rls.internal/
openssl s_client -connect 10.10.0.200:443 -servername anything.rls.internal </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -issuer                    # the wildcard, issued by rls-onprem root CA
```

On any node, `curl -I https://longhorn.rls.internal/` completes the TLS handshake without
`--cacert` and answers `401` until given a password: the node trusts the CA.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| A certificate stays `READY False` | `kubectl describe certificate <name>`, then `kubectl get certificaterequests -n <ns>` and its events. Usually the issuer is not ready: `kubectl describe clusterissuer rls-ca` |
| The browser still warns after importing the CA | The page is served with Ingress-NGINX's built-in fake certificate: the Ingress has no `tls` section, or the default certificate is not ready yet |
| 503 instead of a login prompt | The `ops-ui-basic-auth` Secret is missing in that namespace: re-run `--tags ops-ui` |
| Image pulls from an in-cluster registry fail with `x509: certificate signed by unknown authority` | The node does not trust the CA yet: re-run `--tags tls` (it updates the trust store and restarts containerd) |
