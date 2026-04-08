import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import vm from 'node:vm';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const compiledWebapiPath = path.join(repoRoot, 'dist', 'debug', 'webapi', 'webapi_compiled.js');
const compiledWebapiDir = path.dirname(compiledWebapiPath);
const compiledWebapiRequire = createRequire(compiledWebapiPath);

const bootstrapGlobals = [
  'var cljs = global.cljs || (global.cljs = {});',
  'var clojure = global.clojure || (global.clojure = {});',
  'var host = global.host || (global.host = {});',
  'var services = global.services || (global.services = {});',
  'var shadow = global.shadow || (global.shadow = {});',
].join(' ');

const baselineImports = [
  'goog.debug.error.js',
  'goog.dom.nodetype.js',
  'goog.asserts.asserts.js',
  'goog.reflect.reflect.js',
  'goog.math.long.js',
  'goog.math.integer.js',
  'goog.dom.htmlelement.js',
  'goog.dom.tagname.js',
  'goog.dom.element.js',
  'goog.asserts.dom.js',
  'goog.dom.asserts.js',
  'goog.functions.functions.js',
  'goog.string.typedstring.js',
  'goog.string.const.js',
  'goog.html.trustedtypes.js',
  'goog.html.safescript.js',
  'goog.fs.url.js',
  'goog.fs.blob.js',
  'goog.html.trustedresourceurl.js',
  'goog.string.internal.js',
  'goog.html.safeurl.js',
  'goog.html.safestyle.js',
  'goog.object.object.js',
  'goog.html.safestylesheet.js',
  'goog.flags.flags.js',
  'goog.labs.useragent.useragent.js',
  'goog.labs.useragent.util.js',
  'goog.labs.useragent.highentropy.highentropyvalue.js',
  'goog.labs.useragent.chromium_rebrands.js',
  'goog.labs.useragent.highentropy.highentropydata.js',
  'goog.labs.useragent.browser.js',
  'goog.array.array.js',
  'goog.dom.tags.js',
  'goog.html.safehtml.js',
  'goog.html.uncheckedconversions.js',
  'goog.dom.safe.js',
  'goog.string.string.js',
  'goog.collections.maps.js',
  'goog.structs.structs.js',
  'goog.uri.utils.js',
  'goog.uri.uri.js',
  'goog.string.stringbuffer.js',
  'cljs.core.js',
  'shadow.js.js',
  'clojure.string.js',
  'shadow.js.shim.module$fs.js',
  'shadow.js.shim.module$path.js',
  'host.config.js',
  'host.settings.js',
];

const namespaceImports = {
  api: ['shadow.js.shim.module$$azure$functions.js', 'services.api.js'],
  azureStore: ['shadow.js.shim.module$$azure$data_tables.js', 'shadow.js.shim.module$$azure$storage_blob.js', 'services.azure_store.js'],
  imageGenerator: ['services.image_generator.js'],
  realtime: ['shadow.js.shim.module$crypto.js', 'services.realtime.js'],
};

const imported = new Set();
let bootstrapped = false;

function ensureTestEnv() {
  process.env.ROBOGENE_IMAGE_GENERATOR ??= 'mock';
  process.env.ROBOGENE_ALLOWED_ORIGIN ??= 'http://allowed.test,https://prod.example';
  process.env.ROBOGENE_STORAGE_CONNECTION_STRING ??= 'UseDevelopmentStorage=true';
  process.env.AzureWebJobsStorage ??= 'UseDevelopmentStorage=true';
  process.env.ROBOGENE_ALLOW_DEV_STORAGE_FOR_SMOKE ??= '1';
  process.env.AzureSignalRConnectionString ??= 'Endpoint=https://signalr.example.com;AccessKey=test-access-key;Version=1.0;';
}

function ensureBootstrap() {
  if (bootstrapped) return;
  if (!fs.existsSync(compiledWebapiPath)) {
    throw new Error(`Missing compiled webapi runtime: ${compiledWebapiPath}`);
  }

  const compiledSource = fs.readFileSync(compiledWebapiPath, 'utf8');
  const firstImportAt = compiledSource.indexOf('SHADOW_IMPORT(');
  if (firstImportAt < 0) {
    throw new Error(`Could not find SHADOW_IMPORT bootstrap in ${compiledWebapiPath}`);
  }
  const bootstrapPrelude = compiledSource
    .slice(0, firstImportAt)
    .replace(/^#!.*\r?\n/, '');

  globalThis.__webapi_test_require__ = compiledWebapiRequire;
  globalThis.__webapi_test_module__ = { exports: {} };
  vm.runInThisContext(
    [
      'var require = global.__webapi_test_require__;',
      'var module = global.__webapi_test_module__;',
      `var __filename = ${JSON.stringify(compiledWebapiPath)};`,
      `var __dirname = ${JSON.stringify(compiledWebapiDir)};`,
      bootstrapPrelude,
      '})();',
      '',
    ].join('\n'),
    {
    filename: `${compiledWebapiPath}#bootstrap`,
    },
  );
  vm.runInThisContext(bootstrapGlobals, {
    filename: `${compiledWebapiPath}#globals`,
  });
  bootstrapped = true;
}

function importRuntimeFiles(files) {
  for (const file of files) {
    if (imported.has(file)) continue;
    globalThis.SHADOW_IMPORT(file);
    imported.add(file);
  }
}

export function importWebapiFiles(files) {
  ensureTestEnv();
  ensureBootstrap();
  importRuntimeFiles(baselineImports);
  importRuntimeFiles(files);
}

export function loadWebapiNamespace(name) {
  ensureTestEnv();
  ensureBootstrap();
  importRuntimeFiles(baselineImports);

  const files = namespaceImports[name];
  if (!files) {
    throw new Error(`Unknown webapi test namespace: ${name}`);
  }

  importRuntimeFiles(files);
  return globalThis;
}

export function cljsToJs(value) {
  return globalThis.cljs.core.clj__GT_js(value);
}

export function jsToCljs(value) {
  const cljs = globalThis.cljs.core;
  const options = cljs.PersistentArrayMap.fromArray([cljs.keyword('keywordize-keys'), true], true);
  return cljs.js__GT_clj(value, options);
}
