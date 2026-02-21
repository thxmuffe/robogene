const crypto = require('crypto');

const HUB_NAME = process.env.ROBOGENE_SIGNALR_HUB || 'robogene';
const CONNECTION_SETTING_NAME = process.env.ROBOGENE_SIGNALR_CONNECTION_SETTING || 'AzureSignalRConnectionString';

function parseConnectionString(raw) {
  if (!raw || typeof raw !== 'string') return null;
  const entries = raw.split(';').map((pair) => pair.trim()).filter(Boolean);
  const map = {};
  for (const entry of entries) {
    const eq = entry.indexOf('=');
    if (eq <= 0) continue;
    const key = entry.slice(0, eq).trim();
    const value = entry.slice(eq + 1).trim();
    map[key] = value;
  }
  if (!map.Endpoint || !map.AccessKey) return null;
  return {
    endpoint: map.Endpoint.replace(/\/+$/, ''),
    accessKey: map.AccessKey,
  };
}

function base64Url(input) {
  const buffer = Buffer.isBuffer(input) ? input : Buffer.from(String(input), 'utf8');
  return buffer
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function createJwt(audience, accessKey) {
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const exp = Math.floor(Date.now() / 1000) + 60;
  const payload = base64Url(JSON.stringify({ aud: audience, exp }));
  const content = `${header}.${payload}`;
  const signature = base64Url(
    crypto.createHmac('sha256', Buffer.from(accessKey, 'base64')).update(content).digest()
  );
  return `${content}.${signature}`;
}

function getConnectionConfig() {
  return parseConnectionString(process.env[CONNECTION_SETTING_NAME]);
}

function createClientConnectionInfo() {
  const config = getConnectionConfig();
  if (!config) return null;
  const encodedHub = encodeURIComponent(HUB_NAME);
  const url = `${config.endpoint}/client/?hub=${encodedHub}`;
  const accessToken = createJwt(url, config.accessKey);
  return { url, accessToken };
}

async function publishStateUpdate(data) {
  const config = getConnectionConfig();
  if (!config) return false;

  const audience = `${config.endpoint}/api/v1/hubs/${HUB_NAME}`;
  const token = createJwt(audience, config.accessKey);
  const body = {
    target: 'stateChanged',
    arguments: [data],
  };

  const response = await fetch(audience, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`SignalR publish failed: HTTP ${response.status} ${text}`);
  }
  return true;
}

module.exports = {
  HUB_NAME,
  CONNECTION_SETTING_NAME,
  getConnectionConfig,
  createClientConnectionInfo,
  publishStateUpdate,
};
