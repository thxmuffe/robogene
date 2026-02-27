param functionAppName string
param location string
param storageAccountName string
param appInsightsName string
param signalrName string
param appServicePlanName string = '${functionAppName}-plan'

@secure()
param imageGeneratorKey string

param allowedOrigin string
param imageGenerator string = 'openai'
param signalrHub string = 'robogene'

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: storageAccountName
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

resource signalr 'Microsoft.SignalRService/SignalR@2024-03-01' existing = {
  name: signalrName
}

resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: appServicePlanName
  location: location
  sku: {
    name: 'Y1'
    tier: 'Dynamic'
  }
  kind: 'functionapp'
  properties: {
    reserved: false
  }
}

var storageKey = listKeys(storage.id, '2023-05-01').keys[0].value
var storageConnectionString = 'DefaultEndpointsProtocol=https;EndpointSuffix=${environment().suffixes.storage};AccountName=${storage.name};AccountKey=${storageKey}'
var appInsightsConnectionString = reference(appInsights.id, '2020-02-02').ConnectionString
var signalrPrimaryKey = listKeys(signalr.id, '2024-03-01').primaryKey
var signalrConnectionString = 'Endpoint=https://${signalr.name}.service.signalr.net;AccessKey=${signalrPrimaryKey};Version=1.0;'

resource functionApp 'Microsoft.Web/sites@2023-12-01' = {
  name: functionAppName
  location: location
  kind: 'functionapp'
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    siteConfig: {
      minTlsVersion: '1.2'
      appSettings: [
        {
          name: 'FUNCTIONS_WORKER_RUNTIME'
          value: 'node'
        }
        {
          name: 'FUNCTIONS_EXTENSION_VERSION'
          value: '~4'
        }
        {
          name: 'WEBSITE_NODE_DEFAULT_VERSION'
          value: '~22'
        }
        {
          name: 'WEBSITE_RUN_FROM_PACKAGE'
          value: '1'
        }
        {
          name: 'AzureWebJobsStorage'
          value: storageConnectionString
        }
        {
          name: 'ROBOGENE_STORAGE_CONNECTION_STRING'
          value: storageConnectionString
        }
        {
          name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
          value: appInsightsConnectionString
        }
        {
          name: 'AzureSignalRConnectionString'
          value: signalrConnectionString
        }
        {
          name: 'ROBOGENE_SIGNALR_HUB'
          value: signalrHub
        }
        {
          name: 'ROBOGENE_ALLOWED_ORIGIN'
          value: allowedOrigin
        }
        {
          name: 'ROBOGENE_IMAGE_GENERATOR'
          value: imageGenerator
        }
        {
          name: 'ROBOGENE_IMAGE_GENERATOR_KEY'
          value: imageGeneratorKey
        }
        {
          name: 'AzureWebJobsFeatureFlags'
          value: 'EnableWorkerIndexing'
        }
      ]
      ftpsState: 'FtpsOnly'
    }
  }
  tags: {
    'hidden-link: /app-insights-resource-id': appInsights.id
  }
}

output functionAppNameOut string = functionApp.name
output defaultHostName string = functionApp.properties.defaultHostName
