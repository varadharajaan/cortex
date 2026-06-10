variable "name_prefix" {
  description = "Short prefix used in Azure resource names."
  type        = string
  default     = "cortex"
}

variable "environment" {
  description = "Environment name used for tags and resource names."
  type        = string
  default     = "dev"
}

variable "location" {
  description = "Azure region."
  type        = string
  default     = "eastus2"
}

variable "tags" {
  description = "Additional tags applied to all supported resources."
  type        = map(string)
  default     = {}
}

variable "aks_kubernetes_version" {
  description = "Optional AKS Kubernetes version. Leave null for Azure default."
  type        = string
  default     = null
}

variable "aks_node_count" {
  description = "System node pool size."
  type        = number
  default     = 2
}

variable "aks_node_vm_size" {
  description = "System node pool VM SKU."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "aks_os_disk_size_gb" {
  description = "System node pool OS disk size in GB."
  type        = number
  default     = 128
}

variable "acr_sku" {
  description = "Azure Container Registry SKU."
  type        = string
  default     = "Basic"
}

variable "log_analytics_retention_days" {
  description = "Log Analytics retention in days."
  type        = number
  default     = 30
}

variable "storage_replication_type" {
  description = "Azure Storage replication type."
  type        = string
  default     = "LRS"
}

variable "blob_containers" {
  description = "Private Blob containers for CORTEX cold/archive storage."
  type        = set(string)
  default     = ["cortex-logs-cold", "cortex-dlq-archive"]
}

variable "service_bus_sku" {
  description = "Azure Service Bus namespace SKU."
  type        = string
  default     = "Standard"
}

variable "service_bus_topics" {
  description = "Service Bus topics matching the CORTEX async contracts."
  type        = set(string)
  default = [
    "cortex.logs.events.v1",
    "cortex.logs.events.v1.dlq",
    "cortex.anomalies.v1",
    "cortex.anomalies.v1.dlq",
    "cortex.remediation.outcomes.v1"
  ]
}

variable "service_bus_subscriptions" {
  description = "Default subscriptions for Service Bus topics."
  type = map(object({
    topic              = string
    max_delivery_count = number
  }))
  default = {
    processor = {
      topic              = "cortex.logs.events.v1"
      max_delivery_count = 10
    }
    remediation = {
      topic              = "cortex.anomalies.v1"
      max_delivery_count = 10
    }
    remediation_audit = {
      topic              = "cortex.remediation.outcomes.v1"
      max_delivery_count = 10
    }
  }
}

variable "enable_postgres" {
  description = "Create Azure Database for PostgreSQL Flexible Server for ingest/remediation state."
  type        = bool
  default     = false
}

variable "postgres_admin_login" {
  description = "Postgres administrator login used only when enable_postgres=true."
  type        = string
  default     = "cortexadmin"
}

variable "postgres_admin_password" {
  description = "Postgres administrator password used only when enable_postgres=true."
  type        = string
  default     = null
  sensitive   = true
}

variable "postgres_sku_name" {
  description = "PostgreSQL Flexible Server SKU."
  type        = string
  default     = "B_Standard_B1ms"
}

variable "postgres_storage_mb" {
  description = "PostgreSQL storage in MB."
  type        = number
  default     = 32768
}

variable "enable_redis" {
  description = "Create Azure Cache for Redis for rate limit and dedupe state."
  type        = bool
  default     = false
}

variable "redis_capacity" {
  description = "Azure Cache for Redis capacity."
  type        = number
  default     = 1
}

variable "redis_family" {
  description = "Azure Cache for Redis family."
  type        = string
  default     = "C"
}

variable "redis_sku_name" {
  description = "Azure Cache for Redis SKU."
  type        = string
  default     = "Basic"
}

variable "quickwit_base_url" {
  description = "External Quickwit base URL consumed by Helm values until Quickwit hosting is formalized."
  type        = string
  default     = ""
}

variable "gateway_jwt_secret" {
  description = "Optional gateway JWT secret to seed into Key Vault. Prefer setting this outside committed files."
  type        = string
  default     = null
  sensitive   = true
}
