const { TableClient } = require('@azure/data-tables');
const { BlobServiceClient } = require('@azure/storage-blob');

const connectionString =
  process.env.ROBOGENE_STORAGE_CONNECTION_STRING ||
  process.env.AzureWebJobsStorage;

if (!connectionString) {
  throw new Error('Missing AzureWebJobsStorage or ROBOGENE_STORAGE_CONNECTION_STRING.');
}

const TABLE_META = process.env.ROBOGENE_TABLE_META || 'robogeneMeta';
const TABLE_EPISODES = process.env.ROBOGENE_TABLE_EPISODES || 'robogeneEpisodes';
const TABLE_FRAMES = process.env.ROBOGENE_TABLE_FRAMES || 'robogeneFrames';
const CONTAINER = process.env.ROBOGENE_IMAGE_CONTAINER || 'robogene-images';

const metaClient = TableClient.fromConnectionString(connectionString, TABLE_META);
const episodesClient = TableClient.fromConnectionString(connectionString, TABLE_EPISODES);
const framesClient = TableClient.fromConnectionString(connectionString, TABLE_FRAMES);
const blobService = BlobServiceClient.fromConnectionString(connectionString);
const imageContainer = blobService.getContainerClient(CONTAINER);

let ensured = false;

async function ensure() {
  if (ensured) return;
  await Promise.all([
    metaClient.createTable().catch(() => {}),
    episodesClient.createTable().catch(() => {}),
    framesClient.createTable().catch(() => {}),
  ]);
  await imageContainer.createIfNotExists();
  ensured = true;
}

function parseJson(v, fallback) {
  if (!v) return fallback;
  try {
    return JSON.parse(v);
  } catch {
    return fallback;
  }
}

function normalizeImagePath(storyId, episodeId, frameId) {
  return `stories/${storyId}/episodes/${episodeId}/frames/${frameId}.png`;
}

async function toReadableImageUrl(imagePath) {
  if (!imagePath) return '';
  const blob = imageContainer.getBlockBlobClient(imagePath);
  if (typeof blob.generateSasUrl !== 'function') {
    return blob.url;
  }
  try {
    return await blob.generateSasUrl({
      permissions: 'r',
      startsOn: new Date(Date.now() - 5 * 60 * 1000),
      expiresOn: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000),
    });
  } catch {
    return blob.url;
  }
}

async function uploadDataUrlIfNeeded(storyId, frame) {
  const data = frame.imageDataUrl || '';
  if (!data.startsWith('data:image/png;base64,')) {
    return frame;
  }

  const imagePath = normalizeImagePath(storyId, frame.episodeId, frame.frameId);
  const blob = imageContainer.getBlockBlobClient(imagePath);
  const content = Buffer.from(data.slice('data:image/png;base64,'.length), 'base64');
  await blob.uploadData(content, {
    blobHTTPHeaders: { blobContentType: 'image/png' },
  });

  return {
    ...frame,
    imagePath,
    imageDataUrl: await toReadableImageUrl(imagePath),
  };
}

async function listEntities(client, partitionKey) {
  const out = [];
  for await (const entity of client.listEntities({
    queryOptions: { filter: `PartitionKey eq '${partitionKey}'` },
  })) {
    out.push(entity);
  }
  return out;
}

async function getActiveMeta() {
  try {
    return await metaClient.getEntity('meta', 'active');
  } catch {
    return null;
  }
}

async function setActiveMeta(payload) {
  await metaClient.upsertEntity(
    {
      partitionKey: 'meta',
      rowKey: 'active',
      storyId: payload.storyId,
      revision: Number(payload.revision || 0),
      failedJobsJson: JSON.stringify(payload.failedJobs || []),
    },
    'Replace',
  );
}

async function saveEpisodes(storyId, episodes) {
  const existing = await listEntities(episodesClient, storyId);
  const keep = new Set(episodes.map((e) => e.episodeId));

  for (const e of episodes) {
    await episodesClient.upsertEntity(
      {
        partitionKey: storyId,
        rowKey: e.episodeId,
        payloadJson: JSON.stringify(e),
      },
      'Replace',
    );
  }

  for (const row of existing) {
    if (!keep.has(row.rowKey)) {
      await episodesClient.deleteEntity(storyId, row.rowKey).catch(() => {});
    }
  }
}

async function saveFrames(storyId, frames) {
  const existing = await listEntities(framesClient, storyId);
  const keep = new Set(frames.map((f) => f.frameId));

  const normalized = [];
  for (const frame of frames) {
    const f = await uploadDataUrlIfNeeded(storyId, frame);
    normalized.push(f);
    await framesClient.upsertEntity(
      {
        partitionKey: storyId,
        rowKey: f.frameId,
        episodeId: f.episodeId,
        payloadJson: JSON.stringify(f),
      },
      'Replace',
    );
  }

  for (const row of existing) {
    if (!keep.has(row.rowKey)) {
      const payload = parseJson(row.payloadJson, {});
      if (payload.imagePath) {
        await imageContainer.deleteBlob(payload.imagePath).catch(() => {});
      }
      await framesClient.deleteEntity(storyId, row.rowKey).catch(() => {});
    }
  }

  return normalized;
}

async function loadRows(storyId) {
  const [episodeRows, frameRows] = await Promise.all([
    listEntities(episodesClient, storyId),
    listEntities(framesClient, storyId),
  ]);
  const frames = [];
  for (const row of frameRows) {
    const frame = parseJson(row.payloadJson, null);
    if (!frame) continue;
    if (frame.imagePath) {
      frame.imageDataUrl = await toReadableImageUrl(frame.imagePath);
    }
    frames.push(frame);
  }
  return {
    episodes: episodeRows
      .map((r) => parseJson(r.payloadJson, null))
      .filter(Boolean),
    frames,
  };
}

async function loadOrInitState(initialState) {
  await ensure();

  let meta = await getActiveMeta();
  if (!meta) {
    await setActiveMeta({
      storyId: initialState.storyId,
      revision: initialState.revision || 1,
      failedJobs: initialState.failedJobs || [],
    });
    await saveEpisodes(initialState.storyId, initialState.episodes || []);
    const frames = await saveFrames(initialState.storyId, initialState.frames || []);
    return {
      ...initialState,
      frames,
    };
  }

  const storyId = meta.storyId;
  const revision = Number(meta.revision || 1);
  const failedJobs = parseJson(meta.failedJobsJson, []);
  const rows = await loadRows(storyId);

  return {
    ...initialState,
    storyId,
    revision,
    failedJobs,
    episodes: rows.episodes,
    frames: rows.frames,
  };
}

async function saveState(state) {
  await ensure();
  const storyId = state.storyId;

  const frames = await saveFrames(storyId, state.frames || []);
  await saveEpisodes(storyId, state.episodes || []);
  await setActiveMeta({
    storyId,
    revision: state.revision || 1,
    failedJobs: state.failedJobs || [],
  });

  return {
    ...state,
    frames,
  };
}

module.exports = {
  loadOrInitState,
  saveState,
};
