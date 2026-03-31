# Lobby Architecture Plan

The first lobby prototype is a UI-only staging surface that sits in front of the existing sequence/frame model. Its job is to accept source images from external tools, preserve their order, allow the user to insert intentional gaps, and normalize the staged result into the current `frame` entity shape with empty text fields. The lobby does not become a second persistence model and it does not bypass the backend as source of truth.

The flow is:

1. The user opens the lobby route and selects an existing target sequence.
2. The lobby keeps temporary draft state only in frontend `:view-state`.
3. Each staged slot is either an image data URL or an empty placeholder.
4. When the user imports, the lobby converts staged slots into normal frame create commands in the same order they appear on screen.
5. Those commands go through the existing sync outbox and `/api/entity` flow, so the backend remains authoritative and other clients can receive the resulting entities through normal sync.

For v1, target sequences are limited to entities that already behave as frame owners:

- `chapter` imports map to owner type `saga`
- `character` imports map to owner type `character`

This keeps the prototype aligned with the current app behavior and avoids inventing a second kind of image sequence. The lobby does not need provider-specific logic yet. Source systems such as ChatGPT, Gemini, or Grok are treated as upstream origins, while the lobby only harmonizes imported assets into `robogene`'s canonical format.

This plan stays aligned with `ARCHITECTURE.md`:

- frontend staging is local and temporary
- persistence still flows through the backend
- imported frames reuse the current normalized entity store
- realtime and incremental loading behavior remain unchanged

Future extensions can add richer source metadata, provider-specific connectors, and automated import paths, but those should still terminate in the same normalized frame/entity pipeline rather than introducing a parallel gallery model.
