output "name" {
  value = proxmox_virtual_environment_vm.this.name
}

output "vm_id" {
  value = proxmox_virtual_environment_vm.this.vm_id
}

output "role" {
  value = var.role
}

output "ipv4_address" {
  value = var.ipv4_address
}

output "has_data_disk" {
  value = var.data_disk_gb > 0
}

# Where the data disk appears inside the guest. QEMU names SCSI disks after their drive, so this
# by-id path stays the same across reboots, unlike /dev/sdb.
output "data_disk_device" {
  value = var.data_disk_gb > 0 ? "/dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_drive-scsi1" : null
}
