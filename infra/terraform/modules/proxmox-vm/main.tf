# One Kubernetes node: a full clone of the cloud-init template with a static address.
#
# This module is the only hypervisor-specific code in the pipeline. Its outputs - name, role,
# address - are all Ansible needs, so moving to another hypervisor means replacing this module and
# nothing downstream.

resource "proxmox_virtual_environment_vm" "this" {
  name        = var.name
  vm_id       = var.vm_id
  node_name   = var.proxmox_node
  description = "Realtime GPS on-prem cluster: ${var.role}. Managed by Terraform."

  # Proxmox stores tags sorted and lower-case; sorting here avoids a permanent plan diff.
  tags = sort(distinct(concat(["rls", "terraform", replace(var.role, "_", "-")], var.tags)))

  on_boot         = true
  started         = true
  stop_on_destroy = true

  clone {
    vm_id     = var.template_vm_id
    node_name = coalesce(var.template_node, var.proxmox_node)
    full      = true
  }

  # The template ships qemu-guest-agent, so Proxmox can report addresses and shut the VM down
  # cleanly instead of pulling the power.
  agent {
    enabled = true
  }

  cpu {
    cores = var.cpu_cores
    type  = var.cpu_type
  }

  # No ballooning: the kubelet sizes allocatable memory from what it sees at boot, and a balloon
  # reclaiming memory later turns into OOM kills the scheduler never planned for.
  memory {
    dedicated = var.memory_mb
    floating  = 0
  }

  scsi_hardware = "virtio-scsi-single"

  disk {
    datastore_id = var.datastore_id
    interface    = "scsi0"
    size         = var.os_disk_gb
    discard      = "on"
    iothread     = true
    ssd          = true
  }

  # Longhorn's disk. Left unformatted here; Ansible formats and mounts it, so the filesystem is
  # part of the node configuration rather than of the VM shape.
  dynamic "disk" {
    for_each = var.data_disk_gb > 0 ? [var.data_disk_gb] : []

    content {
      datastore_id = var.datastore_id
      interface    = "scsi1"
      size         = disk.value
      file_format  = "raw"
      discard      = "on"
      iothread     = true
      ssd          = true
    }
  }

  network_device {
    bridge  = var.network_bridge
    model   = "virtio"
    vlan_id = var.vlan_id
  }

  operating_system {
    type = "l26"
  }

  # Ubuntu cloud images log to the serial console; without one, the Proxmox console stays blank.
  serial_device {}

  initialization {
    datastore_id = var.datastore_id

    ip_config {
      ipv4 {
        address = "${var.ipv4_address}/${var.ipv4_prefix_length}"
        gateway = var.ipv4_gateway
      }
    }

    dns {
      servers = var.dns_servers
      domain  = var.dns_domain
    }

    user_account {
      username = var.ssh_username
      keys     = var.ssh_public_keys
    }
  }
}
