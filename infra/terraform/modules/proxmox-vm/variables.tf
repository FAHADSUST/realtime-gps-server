variable "name" {
  description = "VM and host name, e.g. cp-01."
  type        = string
}

variable "role" {
  description = "Cluster role, recorded in the VM description and tags."
  type        = string
}

variable "vm_id" {
  description = "Proxmox VM id. Fixed per node so a re-apply never renumbers machines."
  type        = number
}

variable "proxmox_node" {
  description = "Proxmox node the VM runs on."
  type        = string
}

variable "template_vm_id" {
  description = "Id of the cloud-init template to clone (see scripts/create-ubuntu-template.sh)."
  type        = number
}

variable "template_node" {
  description = "Proxmox node that holds the template. Defaults to proxmox_node."
  type        = string
  default     = null
}

variable "cpu_cores" {
  type = number
}

variable "cpu_type" {
  description = "x86-64-v2-AES keeps live migration possible between different hosts; use host on a single machine."
  type        = string
  default     = "x86-64-v2-AES"
}

variable "memory_mb" {
  type = number
}

variable "datastore_id" {
  description = "Proxmox storage for disks and the cloud-init drive."
  type        = string
}

variable "os_disk_gb" {
  type = number
}

variable "data_disk_gb" {
  description = "Size of a second, empty disk for Longhorn. 0 means no data disk."
  type        = number
  default     = 0
}

variable "network_bridge" {
  type = string
}

variable "vlan_id" {
  description = "VLAN tag on the bridge, or null for an untagged port."
  type        = number
  default     = null
}

variable "ipv4_address" {
  description = "Static address without prefix, e.g. 10.10.0.11."
  type        = string
}

variable "ipv4_prefix_length" {
  type = number
}

variable "ipv4_gateway" {
  type = string
}

variable "dns_servers" {
  type = list(string)
}

variable "dns_domain" {
  type = string
}

variable "ssh_username" {
  description = "User cloud-init creates; Ansible connects as this user."
  type        = string
}

variable "ssh_public_keys" {
  type = list(string)
}

variable "tags" {
  type    = list(string)
  default = []
}

variable "allow_reboot" {
  description = "Let the provider reboot the VM when a change needs it. Off by default: a node must be drained first."
  type        = bool
  default     = false
}
