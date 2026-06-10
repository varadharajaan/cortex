resource "azurerm_container_registry" "main" {
  name                = local.acr_name
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = var.acr_sku
  admin_enabled       = false
  tags                = local.common_tags
}
