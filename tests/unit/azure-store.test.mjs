import test from 'node:test';
import assert from 'node:assert/strict';

import { cljsToJs, loadWebapiNamespace } from '../shared/load-webapi-runtime.mjs';

const { services } = loadWebapiNamespace('azureStore');
const azureStore = services.azure_store;

test('azure_store derive_image_path decodes the blob path and strips duplicate slashes', () => {
  const url = 'https://example.blob.core.windows.net/robogene-images/%2Fworkspaces%2Fdemo%2Fentities%2Fframe-1%2Fimage.png?sv=123';
  assert.equal(
    azureStore.derive_image_path(url),
    'workspaces/demo/entities/frame-1/image.png',
  );
});

test('azure_store parse_image_data_url extracts mime type and payload', () => {
  assert.deepEqual(
    cljsToJs(azureStore.parse_image_data_url('data:image/webp;base64,QUJDRA==')),
    { 'mime-type': 'image/webp', payload: 'QUJDRA==' },
  );
  assert.equal(azureStore.parse_image_data_url('https://example.com/image.png'), null);
});
