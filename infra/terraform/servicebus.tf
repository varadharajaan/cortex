resource "azurerm_servicebus_namespace" "main" {
  name                          = local.servicebus_name
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  sku                           = var.service_bus_sku
  local_auth_enabled            = false
  public_network_access_enabled = true
  tags                          = local.common_tags
}

resource "azurerm_servicebus_topic" "topics" {
  for_each     = var.service_bus_topics
  name         = each.value
  namespace_id = azurerm_servicebus_namespace.main.id

  default_message_ttl = "P14D"
}

resource "azurerm_servicebus_subscription" "subscriptions" {
  for_each = var.service_bus_subscriptions

  name                                      = each.key
  topic_id                                  = azurerm_servicebus_topic.topics[each.value.topic].id
  max_delivery_count                        = each.value.max_delivery_count
  dead_lettering_on_message_expiration      = true
  dead_lettering_on_filter_evaluation_error = true
}
