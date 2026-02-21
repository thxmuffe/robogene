const { app } = require('@azure/functions');
const realtime = require('./realtime');

function allowedOrigins() {
  const raw =
    process.env.ROBOGENE_ALLOWED_ORIGIN ||
    'https://thxmuffe.github.io,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173';
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function corsOrigin(request) {
  const requestOrigin = request?.headers?.get?.('origin') || request?.headers?.get?.('Origin');
  const origins = allowedOrigins();
  if (requestOrigin && origins.includes(requestOrigin)) return requestOrigin;
  return origins[0] || '*';
}

function corsHeaders(request) {
  return {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': corsOrigin(request),
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization',
    Vary: 'Origin',
    'Cache-Control': 'no-store, no-cache, must-revalidate, proxy-revalidate',
  };
}

app.http('signalr-negotiate', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'negotiate',
  handler: async (request) => {
    try {
      const info = realtime.createClientConnectionInfo();
      if (!info) {
        return {
          status: 200,
          jsonBody: {
            disabled: true,
            reason: `Missing ${realtime.CONNECTION_SETTING_NAME}`,
          },
          headers: corsHeaders(request),
        };
      }
      return {
        status: 200,
        jsonBody: info,
        headers: corsHeaders(request),
      };
    } catch (err) {
      return {
        status: 500,
        jsonBody: {
          error: 'SignalR negotiate failed.',
          message: err?.message || String(err),
        },
        headers: corsHeaders(request),
      };
    }
  },
});
