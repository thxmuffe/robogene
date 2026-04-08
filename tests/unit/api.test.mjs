import test from 'node:test';
import assert from 'node:assert/strict';

import { loadWebapiNamespace } from '../shared/load-webapi-runtime.mjs';

const { services } = loadWebapiNamespace('api');
const api = services.api;

test('api cors_headers echoes allowed request origin', () => {
  const request = {
    headers: new Map([['origin', 'https://prod.example']]),
  };

  const headers = api.cors_headers(request);
  assert.equal(headers['Access-Control-Allow-Origin'], 'https://prod.example');
  assert.equal(headers['Content-Type'], 'application/json');
});

test('api cors_headers falls back to the first configured origin for unknown callers', () => {
  const request = {
    headers: new Map([['Origin', 'https://unknown.example']]),
  };

  const headers = api.cors_headers(request);
  assert.equal(headers['Access-Control-Allow-Origin'], 'http://allowed.test');
});
