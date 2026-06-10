resource "azurerm_postgresql_flexible_server" "main" {
  count = var.enable_postgres ? 1 : 0

  name                   = local.postgres_name
  location               = azurerm_resource_group.main.location
  resource_group_name    = azurerm_resource_group.main.name
  version                = "16"
  administrator_login    = var.postgres_admin_login
  administrator_password = var.postgres_admin_password
  sku_name               = var.postgres_sku_name
  storage_mb             = var.postgres_storage_mb
  backup_retention_days  = 7
  tags                   = local.common_tags
}

resource "azurerm_postgresql_flexible_server_database" "databases" {
  for_each = var.enable_postgres ? toset(["cortex_ingest", "cortex_remediation"]) : toset([])

  name      = each.value
  server_id = azurerm_postgresql_flexible_server.main[0].id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_redis_cache" "main" {
  count = var.enable_redis ? 1 : 0

  name                          = local.redis_name
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  capacity                      = var.redis_capacity
  family                        = var.redis_family
  sku_name                      = var.redis_sku_name
  minimum_tls_version           = "1.2"
  public_network_access_enabled = true
  tags                          = local.common_tags
}
