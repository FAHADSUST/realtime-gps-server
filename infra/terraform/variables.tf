# ---------------------------------------------------------------------------------------------
# Proxmox access
# ---------------------------------------------------------------------------------------------

variable "proxmox_endpoint" {
  description = "Proxmox VE API URL, e.g. https://pve-01.rls.internal:8006/"
  type        = string
}

variable "proxmox_api_token" {
  description = "API token as user@realm!token-id=secret. Pass it as TF_VAR_proxmox_api_token, never in a file."
  type        = string
  sensitive   = true
}

variable "proxmox_insecure_tls" {
  description = "Skip TLS verification of the Proxmox API. Only for a fresh install with its self-signed certificate."
  type        = bool
  default     = false
}

variable "default_proxmox_node" {
  description = "Proxmox node a VM is placed on unless its entry in nodes names another."
  type        = string
}

variable "template" {
  description = "Cloud-init template created by scripts/create-ubuntu-template.sh."
  type = object({
    vm_id     = number
    node_name = string
  })
}

variable "datastore_id" {
  description = "Proxmox storage for VM disks and cloud-init drives."
  type        = string
  default     = "local-lvm"
}

variable "cpu_type" {
  description = "x86-64-v2-AES allows live migration between different CPUs; host is faster on a single machine."
  type        = string
  default     = "x86-64-v2-AES"
}

variable "allow_vm_reboot" {
  description = "Allow Terraform to reboot VMs to apply a change. Set only for a targeted apply on a drained node."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------------------------

variable "network" {
  description = "The node network every VM attaches to."
  type = object({
    bridge      = string
    vlan_id     = optional(number)
    cidr        = string
    gateway     = string
    dns_servers = list(string)
    dns_domain  = string
  })

  validation {
    condition     = can(cidrhost(var.network.cidr, 0))
    error_message = "network.cidr must be a valid IPv4 CIDR such as 10.10.0.0/24."
  }
}

variable "cluster_addresses" {
  description = <<-EOT
    Addresses on the node network that belong to the cluster rather than to a VM:
    the Kubernetes API virtual IP (kube-vip), the MetalLB pool, and the fixed ingress address
    taken from that pool. Terraform owns every address on the node network so that all of them
    are checked for collisions in one place.
  EOT
  type = object({
    api_vip       = string
    lb_pool_start = string
    lb_pool_end   = string
    ingress_ip    = string
  })
}

variable "ssh" {
  description = "Login cloud-init creates on every VM; Ansible connects with it."
  type = object({
    username    = string
    public_keys = list(string)
  })

  validation {
    condition     = length(var.ssh.public_keys) > 0
    error_message = "At least one SSH public key is required: the VMs have no password login."
  }
}

# ---------------------------------------------------------------------------------------------
# Nodes
# ---------------------------------------------------------------------------------------------

variable "nodes" {
  description = <<-EOT
    Every machine in the cluster, keyed by host name. Adding an entry and re-applying adds a node;
    Ansible then joins it. Ids and addresses are explicit so a node keeps its identity forever.
  EOT
  type = map(object({
    role         = string
    vm_id        = number
    ip           = string
    cores        = number
    memory_mb    = number
    os_disk_gb   = number
    data_disk_gb = optional(number, 0)
    proxmox_node = optional(string)
  }))

  validation {
    condition     = alltrue([for n in values(var.nodes) : contains(["control_plane", "worker"], n.role)])
    error_message = "Every node's role must be control_plane or worker."
  }

  # etcd needs a majority to accept writes: two control planes tolerate no failure at all, so an
  # even count costs a machine and buys nothing.
  validation {
    condition     = length([for n in values(var.nodes) : n if n.role == "control_plane"]) % 2 == 1
    error_message = "Use an odd number of control planes (1, 3 or 5) so etcd keeps quorum."
  }

  validation {
    condition     = length([for n in values(var.nodes) : n if n.role == "worker"]) >= 1
    error_message = "At least one worker is required; control planes do not run workloads."
  }

  validation {
    condition     = length(distinct([for n in values(var.nodes) : n.ip])) == length(var.nodes)
    error_message = "Two nodes share an IP address."
  }

  validation {
    condition     = length(distinct([for n in values(var.nodes) : n.vm_id])) == length(var.nodes)
    error_message = "Two nodes share a Proxmox VM id."
  }

  validation {
    condition     = alltrue([for n in values(var.nodes) : n.role != "worker" || n.data_disk_gb > 0])
    error_message = "Every worker needs a data disk (data_disk_gb > 0): Longhorn stores volumes there."
  }
}
