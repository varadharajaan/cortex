resource "azurerm_key_vault" "main" {
  name                       = local.key_vault_name
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  soft_delete_retention_days = 7
  purge_protection_enabled   = true
  rbac_authorization_enabled = true
  tags                       = local.common_tags
}

resource "azurerm_role_assignment" "current_key_vault_admin" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Administrator"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_secret" "gateway_jwt_secret" {
  count        = var.gateway_jwt_secret == null ? 0 : 1
  name         = "cortex-gateway-jwt-secret"
  value        = var.gateway_jwt_secret
  key_vault_id = azurerm_key_vault.main.id
  tags         = local.common_tags

  depends_on = [azurerm_role_assignment.current_key_vault_admin]
}
