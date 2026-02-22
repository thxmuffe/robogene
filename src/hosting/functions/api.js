const { app } = require('@azure/functions');

// Azure Functions runs on the Node worker here, so this bootstrap file
// loads the compiled ClojureScript module and provides startup diagnostics.
const errorDetails = (err) => ({
  name: err?.name || 'Error',
  message: err?.message || String(err),
  stack: err?.stack || null,
});

const corsOrigin = (request) => {
  const origin = request?.headers?.get?.('origin') || request?.headers?.get?.('Origin');
  return origin || '*';
};

const diagnosticHeaders = (request) => ({
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': corsOrigin(request),
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization',
  Vary: 'Origin',
  'Cache-Control': 'no-store, no-cache, must-revalidate, proxy-revalidate',
});

const startupFailureResponse = (request, err) => ({
  status: 500,
  jsonBody: {
    error: 'Backend failed during startup.',
    detail: errorDetails(err),
    hint: 'Run `npm run build:backend` and fix compile/runtime errors in ClojureScript backend.',
  },
  headers: diagnosticHeaders(request),
});

try {
  require('./api_compiled.js');
} catch (err) {
  console.error('[robogene] Failed to load src/hosting/functions/api_compiled.js');
  console.error(err);

  const handler = (request) => startupFailureResponse(request, err);

  app.http('startup-diagnostic-state', {
    methods: ['GET'],
    authLevel: 'anonymous',
    route: 'state',
    handler,
  });

  app.http('startup-diagnostic-generate-frame', {
    methods: ['POST'],
    authLevel: 'anonymous',
    route: 'generate-frame',
    handler,
  });

  app.http('startup-diagnostic-options', {
    methods: ['OPTIONS'],
    authLevel: 'anonymous',
    route: '{*path}',
    handler: (request) => ({
      status: 204,
      headers: diagnosticHeaders(request),
    }),
  });
}
