# Unified Entity Refactoring Plan

## Overview
Consolidate all domain-specific entities (Saga, Roster, Chapter, Character, Frame) into a single, unified "Entity" model. Entities are categorized into two kinds: **Sequence** (a collection) and **Item** (a single unit, e.g., a frame). Both share the same data structure.

### The Unified Entity Structure
- `id`: Unique identifier (UUID).
- `vanityRole`: UI-only hint ("saga", "chapter", "roster", "character", "frame").
- `title`: Primary text field.
- `description`: Secondary text field (acts as subtitle for items).
- `children`: Ordered array of Entity IDs.
- `payload`: Flexible map for media URLs, metadata, etc.

---

## Phase 1: WebAPI & Database (Current Focus)
*Goal: Consolidate backend storage and logic into generic patterns.*

### 1. Database Consolidation (`azure_store.cljs`)
- Implement a single table (e.g., `Entities`).
- PartitionKey: Workspace/Root ID.
- RowKey: Entity ID.
- Unified `save-entity!`, `load-entities`, `delete-entity!` functions.

### 2. Consolidated Business Logic (`services/entity.cljs`)
- Merge `chapter.cljs`, `character.cljs`, etc., into a new generic service.
- Unified operations: `add-entity!`, `update-entity!`, `delete-entity!`.
- Entity creation logic now focuses on `vanityRole`.

### 3. Generic API Endpoints (`api.cljs`)
- Implement generic endpoints:
  - `POST /api/entity` (Create)
  - `PATCH /api/entity/:id` (Update)
  - `DELETE /api/entity/:id` (Delete)
- Update SignalR to broadcast generic `entity-updated` events.

---

## Phase 2: WebApp State & Transport
*Goal: Simplify the frontend to store a flat "pool" of entities.*

1. **Generic State:** Replace specific collections with a single `:entities` map.
2. **Unified Events:** Replace domain-specific handlers with generic ones.
3. **Smart Model:** Reconstruct hierarchy in `derived-state` via `children` arrays.

---

## Phase 3: WebApp UI
*Goal: Config-driven rendering based on child types and vanity roles.*

1. **Vanity Router:** Map existing URLs to generic navigation events.
2. **Generic Layouts:**
   - `VerticalStack`: For sequences containing sequences.
   - `HorizontalGrid`: For sequences containing items.
3. **Entity Page:** A unified page component that selects the layout based on data.

---

## Phase 4: Clean & Validate
1. **Code Deletion:** Remove all redundant domain-specific code.
2. **Test Migration:** Update E2E tests to use the new generic API.
