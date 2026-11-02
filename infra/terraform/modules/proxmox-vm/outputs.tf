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
