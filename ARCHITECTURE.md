# Architecture

## Core Direction

- Prefer a push-first, reactive data model.
- Treat the backend as the source of truth.
- Treat the frontend store as a local replicated cache of currently relevant data.
- Prefer data replication over imperative "go fetch after event" flows.

## Transport Split

- Use HTTP for initial load and incremental loading.
- Use SignalR for ongoing realtime sync after data is loaded.
- Do not use polling for normal sync.

## Realtime Model

- Push deltas with enough data for clients to apply directly.
- Avoid thin event notifications that force every client to investigate or refetch.
- Prefer messages like "here is the changed entity / removed id / updated parent linkage" over "something changed".
- Full fetch should be a recovery path only:
  - initial app load
  - reconnect after missed realtime updates
  - rare desync or incomplete payload cases

## Frontend Store

- Keep a normalized entity store keyed by id.
- Components should subscribe to the narrowest useful slice.
- Prefer subscribing by entity id instead of subscribing to the whole `:entities` map.
- One changed entity should rerender only the components that depend on that entity.

## Incremental Loading

- Do not load the full database into the browser by default.
- Prefer incremental loading from the backend for search and large collections.
- Use chunk/cursor based loading semantics rather than full-dataset fetches.
- Load only the data needed for the current route and visible UI.

## Images

- Do not attempt binary delta sync for images.
- Push image metadata/status via SignalR.
- Deliver image bytes via normal URL fetches and browser/cache/CDN behavior.
- When an image changes, publish a new image URL or versioned URL.

## Shared-Experience Goal

- If many browsers are viewing the same data, one change should be pushed once by the backend and applied locally by all clients.
- Avoid fan-out patterns where each client receives a notification and then performs the same follow-up fetch.

## Guidance For Future Changes

- Prefer the smallest implementation that moves the app toward:
  - push-first sync
  - delta replication
  - narrow subscriptions
  - incremental loading
- New externally sourced or user-imported data should enter as isolated draft data first and remain unlinked from the main entity graph until the user explicitly confirms attachment/publication.
- Avoid introducing alternate sync styles that pull the app back toward broad polling, broad refetches, or full-db browser loads.
