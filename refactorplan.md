# Unified Entity Refactoring Plan (updated)

## Current State
- WebAPI and storage are already unified: one flat table, one entry point, SignalR emits generic entity events.
- Domain labels (saga/roster/chapter/character/frame) are now purely **vanity** on the client; data types are only **sequence** (has children) and **item** (leaf).

## Data Model (authoritative)
- `id`: UUID
- `kind`: `sequence` | `item`
- `title`, `description`
- `children`: ordered entity ids (empty for items)
- `vanityRole`: UI hint only (optional; keeps legacy naming while migrating UI copy)
- `payload`: media/metadata (imageUrl, status, etc.)

## Phase 1 — WebAPI Cleanup (done)
- Keep only generic endpoints: create/update/delete entity, list entities, SignalR broadcast for entity changes.
- Remove chapter/character/roster/saga-specific routes and handlers from the API codebase.

## Phase 2 — WebApp State & Transport (in progress)
1) Replace per-type lists (`:sagas`, `:rosters`, `:saga`, `:roster`, `:gallery-items`) with the flat `:entities` pool as the source of truth.
2) Rewire fetch/init to populate `:entities` directly from the generic API response; drop legacy `:latest-state` coupling.
3) Update transport layer to post generic entity mutations (create/update/delete) and stop emitting per-type commands.
4) Maintain `:derived-state` (children-by-parent) from the pool; eliminate per-type selectors.

## Phase 3 — WebApp UI
1) Routing: map all existing hashes to generic entity ids; frame detail uses item ids.
2) Gallery/Frame pages: use `entity-gallery-page` / `entity-frame-page` for both sequences and items; remove `chapter-page`, `roster-page`, `gallery-page` legacy variants.
3) Components: make `sequence`/`item` components render based on `kind` + `vanityRole`; purge role-specific branches.

## Phase 4 — Tests & Cleanup
1) Update fixtures and E2E flows to seed entities via the generic endpoints.
2) Delete legacy reducers/handlers/helpers once UI is fully on the entity pool.
3) Remove unused API docs and client transport code for per-type routes.
