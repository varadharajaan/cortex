locals {
  dashed_prefix  = trim(replace(lower("${var.name_prefix}-${var.environment}"), "/[^a-z0-9-]/", "-"), "-")
  compact_prefix = replace(local.dashed_prefix, "/[^a-z0-9]/", "")
  suffix         = random_string.suffix.result

  resource_group_name = "rg-${local.dashed_prefix}-${local.suffix}"
  aks_name            = "aks-${local.dashed_prefix}-${local.suffix}"
  aks_dns_prefix      = substr("aks-${local.compact_prefix}-${local.suffix}", 0, 54)
  acr_name            = substr("acr${local.compact_prefix}${local.suffix}", 0, 50)
  key_vault_name      = substr("kv-${local.dashed_prefix}-${local.suffix}", 0, 24)
  storage_name        = substr("st${local.compact_prefix}${local.suffix}", 0, 24)
  servicebus_name     = substr("sb-${local.dashed_prefix}-${local.suffix}", 0, 50)
  log_workspace_name  = "log-${local.dashed_prefix}-${local.suffix}"
  app_insights_name   = "appi-${local.dashed_prefix}-${local.suffix}"
  postgres_name       = substr("psql-${local.dashed_prefix}-${local.suffix}", 0, 63)
  redis_name          = substr("redis-${local.dashed_prefix}-${local.suffix}", 0, 63)

  common_tags = merge({
    app         = "cortex"
    environment = var.environment
    managed_by  = "terraform"
    phase       = "P12"
  }, var.tags)
}
