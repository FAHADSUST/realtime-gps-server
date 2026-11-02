output "nodes" {
  description = "Every VM with its role and address."
  value = {
    for name, node in module.node : name => {
      role  = node.role
      ip    = node.ipv4_address
      vm_id = node.vm_id
    }
  }
}

output "kubernetes_api_vip" {
  description = "Address of the Kubernetes API once kube-vip is running."
  value       = var.cluster_addresses.api_vip
}

output "ingress_ip" {
  description = "Address every *.<domain> DNS record should point to."
  value       = var.cluster_addresses.ingress_ip
}

output "ansible_inventory" {
  description = "Inventory written for Ansible."
  value       = abspath(local_file.ansible_inventory.filename)
}
