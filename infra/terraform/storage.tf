resource "azurerm_storage_account" "main" {
  name                            = local.storage_name
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  account_tier                    = "Standard"
  account_replication_type        = var.storage_replication_type
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  tags                            = local.common_tags

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 7
    }

    container_delete_retention_policy {
      days = 7
    }
  }
}

resource "azurerm_storage_container" "containers" {
  for_each              = var.blob_containers
  name                  = each.value
  storage_account_id    = azurerm_storage_account.main.id
  container_access_type = "private"
}
