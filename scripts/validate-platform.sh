#!/usr/bin/env bash
# Checks the on-prem platform code without a cluster: everything that can be caught before a change
# reaches real machines. CI runs it on every change to the platform; it runs the same on any Linux
# or macOS machine, or WSL.
#
#   ./scripts/validate-platform.sh                    # every check
#   ./scripts/validate-platform.sh terraform ansible  # some: yaml shell terraform ansible charts observability
#
# Each check needs its tools on PATH; CI pins their versions (.github/workflows/platform.yml):
#   terraform, yamllint, the shell linter shellcheck, ansible-playbook, ansible-galaxy,
#   ansible-lint, helm, kubeconform, promtool, alloy, and python3 with PyYAML and Jinja2.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$(mktemp -d)}"
mkdir -p "${OUT_DIR}"
PYTHON="${PYTHON:-python3}"
CRD_SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
cd "${ROOT_DIR}"

step() { printf '\n==> %s\n' "$*"; }

need() {
  for tool in "$@"; do
    command -v "${tool}" >/dev/null || { echo "ERROR: ${tool} is not on PATH." >&2; exit 1; }
  done
}

check_yaml() {
  need yamllint
  step "yamllint"
  yamllint -s infra/ansible deploy/kubernetes .github
}

check_shell() {
  need shellcheck
  step "shellcheck"
  shellcheck scripts/*.sh infra/terraform/scripts/*.sh gateway/kong/docker-entrypoint-gps.sh \
    deploy/kubernetes/gps/files/mysql-init.sh infra/ansible/roles/etcd_backup/files/etcd-snapshot
}

check_terraform() {
  need terraform
  step "Terraform: format, validate, plan the example topology"
  terraform -chdir=infra/terraform fmt -check -recursive
  terraform -chdir=infra/terraform init -backend=false -input=false >/dev/null
  terraform -chdir=infra/terraform validate
  # The plan runs every variable validation and the address plan against the example values. No
  # refresh, so nothing is contacted; the token is a dummy.
  TF_VAR_proxmox_api_token='ci@pve!plan=00000000-0000-0000-0000-000000000000' \
    terraform -chdir=infra/terraform plan -input=false -refresh=false -lock=false \
    -var-file=terraform.tfvars.example -out="${OUT_DIR}/example.tfplan" >/dev/null
  echo "plan OK"
}

check_ansible() {
  need ansible-playbook ansible-galaxy ansible-lint "${PYTHON}"
  step "Ansible: collections, syntax, ansible-lint, unit tests"
  (
    cd infra/ansible
    ansible-galaxy collection install -r requirements.yml -p collections >/dev/null
    ansible-playbook -i inventory/hosts.example.yml site.yml upgrade.yml --syntax-check
    ansible-lint
  )
  "${PYTHON}" -m unittest discover -s infra/ansible/tests
}

check_charts() {
  need helm kubeconform "${PYTHON}"
  step "Helm: lint the charts of this repository"
  helm lint deploy/kubernetes/gitops
  helm lint deploy/kubernetes/registry
  helm lint deploy/kubernetes/gps --set images.tag=ci
  step "Render every Argo CD application as Argo CD would, and validate the result"
  "${PYTHON}" scripts/ci/render_gitops.py "${OUT_DIR}/apps" --set apps.gps.enabled=true --set apps.gps.imageTag=ci
  kubeconform -strict -summary -kubernetes-version 1.36.0 -skip CustomResourceDefinition \
    -schema-location default -schema-location "${CRD_SCHEMAS}" "${OUT_DIR}"/apps/*.yaml
}

check_observability() {
  need promtool alloy
  [ -f "${OUT_DIR}/apps/alloy/config.alloy" ] || check_charts
  step "promtool: alert rules and dashboard queries"
  promtool check rules "${OUT_DIR}"/apps/rules/*.yaml
  step "alloy validate: the log pipeline"
  LOKI_PUSH_URL=http://loki:3100/loki/api/v1/push CLUSTER_NAME=ci HOSTNAME=ci \
    alloy validate "${OUT_DIR}/apps/alloy/config.alloy"
  echo "Alloy configuration OK"
}

if [ "$#" -eq 0 ]; then
  set -- yaml shell terraform ansible charts observability
fi
for group in "$@"; do
  case "${group}" in
    yaml | shell | terraform | ansible | charts | observability) "check_${group}" ;;
    *) echo "ERROR: unknown check '${group}'" >&2; exit 1 ;;
  esac
done
printf '\nAll checks passed. Output in %s\n' "${OUT_DIR}"
