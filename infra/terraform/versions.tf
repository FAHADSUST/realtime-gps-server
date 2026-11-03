terraform {
  required_version = ">= 1.9"

  required_providers {
    proxmox = {
      source  = "bpg/proxmox"
      version = "~> 0.119"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.9"
    }
  }

  # State is local by default. For a team, move it to a shared backend that also lives on-prem,
  # e.g. PostgreSQL:
  #
  # backend "pg" {
  #   conn_str = "postgres://terraform@db.rls.internal/terraform_state?sslmode=require"
  # }
}
