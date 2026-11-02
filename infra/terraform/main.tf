locals {
  prefix_length = tonumber(split("/", var.network.cidr)[1])

  node_ips = [for n in values(var.nodes) : n.ip]

  # Cluster addresses must never be handed to a VM as well. A clash here would surface much later
  # as a flapping API endpoint or an ingress that answers from the wrong machine.
  address_conflicts = concat(
    contains(local.node_ips, var.cluster_addresses.api_vip) ? ["api_vip ${var.cluster_addresses.api_vip} is also a node address"] : [],
    contains(local.node_ips, var.cluster_addresses.ingress_ip) ? ["ingress_ip ${var.cluster_addresses.ingress_ip} is also a node address"] : [],
    var.cluster_addresses.api_vip == var.cluster_addresses.ingress_ip ? ["api_vip and ingress_ip are the same address"] : [],
  )
}

resource "terraform_data" "address_plan" {
  input = local.address_conflicts

  lifecycle {
    precondition {
      condition     = length(local.address_conflicts) == 0
      error_message = "Address plan conflict: ${join("; ", local.address_conflicts)}"
    }
  }
}

module "node" {
  source   = "./modules/proxmox-vm"
  for_each = var.nodes

  name           = each.key
  role           = each.value.role
  vm_id          = each.value.vm_id
  proxmox_node   = coalesce(each.value.proxmox_node, var.default_proxmox_node)
  template_vm_id = var.template.vm_id
  template_node  = var.template.node_name

  cpu_cores = each.value.cores
  cpu_type  = var.cpu_type
  memory_mb = each.value.memory_mb

  datastore_id = var.datastore_id
  os_disk_gb   = each.value.os_disk_gb
  data_disk_gb = each.value.data_disk_gb

  network_bridge     = var.network.bridge
  vlan_id            = var.network.vlan_id
  ipv4_address       = each.value.ip
  ipv4_prefix_length = local.prefix_length
  ipv4_gateway       = var.network.gateway
  dns_servers        = var.network.dns_servers
  dns_domain         = var.network.dns_domain

  ssh_username    = var.ssh.username
  ssh_public_keys = var.ssh.public_keys

  depends_on = [terraform_data.address_plan]
}
