#!/usr/bin/env bash
# Build the Ubuntu 24.04 cloud-init template every cluster VM is cloned from.
# Run once, as root, on the Proxmox node that will hold the template.
#
#   TEMPLATE_ID=9000 STORAGE=local-lvm BRIDGE=vmbr0 ./create-ubuntu-template.sh
#
# Requires libguestfs-tools on the Proxmox host (apt install libguestfs-tools) to bake the guest
# agent into the image: without it Proxmox cannot see the VM's addresses or shut it down cleanly.
set -euo pipefail

TEMPLATE_ID="${TEMPLATE_ID:-9000}"
TEMPLATE_NAME="${TEMPLATE_NAME:-ubuntu-2404-cloudinit}"
STORAGE="${STORAGE:-local-lvm}"
BRIDGE="${BRIDGE:-vmbr0}"
RELEASE_URL="${RELEASE_URL:-https://cloud-images.ubuntu.com/noble/current}"
IMAGE="noble-server-cloudimg-amd64.img"

if qm status "${TEMPLATE_ID}" >/dev/null 2>&1; then
  echo "VM ${TEMPLATE_ID} already exists - remove it first or choose another TEMPLATE_ID." >&2
  exit 1
fi
command -v virt-customize >/dev/null || { echo "virt-customize missing: apt install libguestfs-tools" >&2; exit 1; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT
cd "${WORKDIR}"

echo "==> Downloading ${IMAGE}"
curl -fsSLO "${RELEASE_URL}/${IMAGE}"
curl -fsSLO "${RELEASE_URL}/SHA256SUMS"
grep " \*\?${IMAGE}\$" SHA256SUMS | sha256sum -c -

echo "==> Baking in qemu-guest-agent"
# The image ships an empty machine-id so every clone generates its own; kubeadm refuses nodes
# that share one, so make sure customisation did not fill it in.
virt-customize -a "${IMAGE}" \
  --install qemu-guest-agent \
  --truncate /etc/machine-id

echo "==> Creating template ${TEMPLATE_ID}"
qm create "${TEMPLATE_ID}" \
  --name "${TEMPLATE_NAME}" \
  --ostype l26 \
  --memory 2048 \
  --cores 2 \
  --cpu x86-64-v2-AES \
  --net0 "virtio,bridge=${BRIDGE}" \
  --scsihw virtio-scsi-single \
  --agent enabled=1 \
  --serial0 socket \
  --vga serial0

qm set "${TEMPLATE_ID}" --scsi0 "${STORAGE}:0,import-from=${WORKDIR}/${IMAGE},discard=on,ssd=1,iothread=1"
qm set "${TEMPLATE_ID}" --ide2 "${STORAGE}:cloudinit"
qm set "${TEMPLATE_ID}" --boot order=scsi0
qm template "${TEMPLATE_ID}"

echo "==> Template ${TEMPLATE_NAME} (${TEMPLATE_ID}) is ready."
