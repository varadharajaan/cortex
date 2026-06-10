output "resource_group_name" {
  description = "Resource group name."
  value       = azurerm_resource_group.main.name
}

output "aks_name" {
  description = "AKS cluster name."
  value       = azurerm_kubernetes_cluster.main.name
}

output "aks_oidc_issuer_url" {
  description = "AKS OIDC issuer URL for workload identity federation."
  value       = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "acr_login_server" {
  description = "ACR login server used by Helm image repository overrides."
  value       = azurerm_container_registry.main.login_server
}

output "key_vault_name" {
  description = "Key Vault name."
  value       = azurerm_key_vault.main.name
}

output "key_vault_uri" {
  description = "Key Vault URI."
  value       = azurerm_key_vault.main.vault_uri
}

output "application_insights_connection_string" {
  description = "Application Insights connection string."
  value       = azurerm_application_insights.main.connection_string
  sensitive   = true
}

output "storage_account_name" {
  description = "Storage account for Blob cold/archive data."
  value       = azurerm_storage_account.main.name
}

output "blob_container_names" {
  description = "Blob containers created for CORTEX."
  value       = [for container in azurerm_storage_container.containers : container.name]
}

output "service_bus_namespace_name" {
  description = "Service Bus namespace name."
  value       = azurerm_servicebus_namespace.main.name
}

output "service_bus_topic_names" {
  description = "Service Bus topics."
  value       = [for topic in azurerm_servicebus_topic.topics : topic.name]
}

output "postgres_fqdn" {
  description = "Optional PostgreSQL FQDN when enable_postgres=true."
  value       = var.enable_postgres ? azurerm_postgresql_flexible_server.main[0].fqdn : null
}

output "redis_hostname" {
  description = "Optional Redis hostname when enable_redis=true."
  value       = var.enable_redis ? azurerm_redis_cache.main[0].hostname : null
}

output "helm_image_repository_overrides" {
  description = "Image repository overrides for the P11 Helm chart."
  value = {
    "cortex-eureka.image.repository"      = "${azurerm_container_registry.main.login_server}/eureka-server"
    "cortex-gateway.image.repository"     = "${azurerm_container_registry.main.login_server}/log-gateway"
    "cortex-ingest.image.repository"      = "${azurerm_container_registry.main.login_server}/log-ingest-service"
    "cortex-processor.image.repository"   = "${azurerm_container_registry.main.login_server}/log-processor-service"
    "cortex-remediation.image.repository" = "${azurerm_container_registry.main.login_server}/log-remediation-service"
    "cortex-indexer.image.repository"     = "${azurerm_container_registry.main.login_server}/log-indexer-service"
    "cortex-monitoring.image.repository"  = "${azurerm_container_registry.main.login_server}/log-monitoring-service"
    "cortex-echo.image.repository"        = "${azurerm_container_registry.main.login_server}/log-echo-service"
  }
}
