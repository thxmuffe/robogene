param location string
param storageAccountName string
param functionAppName string
param appInsightsName string
param signalrName string

@secure()
param imageGeneratorKey string

param allowedOrigin string
param imageGenerator string = 'openai'
param signalrHub string = 'robogene'

module appInsights 'modules/app_insights.bicep' = {
  name: 'appInsightsDeploy'
  params: {
    appInsightsName: appInsightsName
    location: location
  }
}

module aux 'modules/signalr.bicep' = {
  name: 'signalrDeploy'
  params: {
    signalrName: signalrName
    location: location
  }
}

module db 'modules/database.bicep' = {
  name: 'databaseDeploy'
  params: {
    storageAccountName: storageAccountName
    location: location
  }
}

module fn 'modules/function_app.bicep' = {
  name: 'functionAppDeploy'
  params: {
    functionAppName: functionAppName
    location: location
    storageAccountName: storageAccountName
    appInsightsName: appInsightsName
    signalrName: signalrName
    imageGeneratorKey: imageGeneratorKey
    allowedOrigin: allowedOrigin
    imageGenerator: imageGenerator
    signalrHub: signalrHub
  }
}

output defaultHostName string = fn.outputs.defaultHostName
output appInsightsId string = appInsights.outputs.appInsightsId
