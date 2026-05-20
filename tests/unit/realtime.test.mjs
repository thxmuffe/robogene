import test from 'node:test';
import assert from 'node:assert/strict';

import { cljsToJs, loadWebapiNamespace } from '../shared/load-webapi-runtime.mjs';

const { services } = loadWebapiNamespace('realtime');
const realtime = services.realtime;

function decodeBase64UrlJson(value) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padding = '='.repeat((4 - (normalized.length % 4)) % 4);
  return JSON.parse(Buffer.from(normalized + padding, 'base64url').toString('utf8'));
}

test('realtime parse_connection_string extracts endpoint and access key', () => {
  const parsed = cljsToJs(
    realtime.parse_connection_string('Endpoint=https://signalr.example.com/;AccessKey=secret;Version=1.0;'),
  );

  assert.deepEqual(parsed, {
    endpoint: 'https://signalr.example.com',
    'access-key': 'secret',
  });
  assert.equal(realtime.parse_connection_string('Endpoint=https://signalr.example.com/;Version=1.0;'), null);
});

test('realtime create_jwt encodes the audience and ttl into the payload', () => {
  const token = realtime.create_jwt('https://signalr.example.com/client/?hub=robogene', 'secret', 120);
  const [headerPart, payloadPart, signaturePart] = token.split('.');
  const header = decodeBase64UrlJson(headerPart);
  const payload = decodeBase64UrlJson(payloadPart);

  assert.deepEqual(header, { alg: 'HS256', typ: 'JWT' });
  assert.equal(payload.aud, 'https://signalr.example.com/client/?hub=robogene');
  assert.equal(payload.exp - payload.iat, 120);
  assert.ok(signaturePart.length > 0);
});

test('realtime create_client_connection_info uses the configured SignalR endpoint', () => {
  const info = cljsToJs(realtime.create_client_connection_info());

  assert.equal(info.url, 'https://signalr.integration.test/client/?hub=robogene');
  assert.equal(typeof info.accessToken, 'string');
  assert.ok(info.accessToken.split('.').length === 3);
});
