param signalrName string
param location string
param skuName string = 'Standard_S1'
param skuCapacity int = 1

resource signalr 'Microsoft.SignalRService/SignalR@2024-03-01' = {
  name: signalrName
  location: location
  sku: {
    name: skuName
    capacity: skuCapacity
  }
  properties: {
    features: [
      {
        flag: 'ServiceMode'
        value: 'Default'
      }
    ]
    publicNetworkAccess: 'Enabled'
  }
}

output signalrId string = signalr.id
output signalrNameOut string = signalr.name
