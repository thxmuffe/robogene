# RoboGene API Documentation

## Overview

The RoboGene API is built with Azure Functions and ClojureScript. All endpoints return JSON and support CORS.

**Base URL**: `http://localhost:56600/api` (local), `https://robogene-func-prod.azurewebsites.net/api` (production)

## Authentication

Currently, no authentication is required. Realtime updates use Azure SignalR.

## Data Model

### Saga
Top-level entity representing a story arc.
- `sagaId`: UUID
- `name`: string
- `description`: string

### Roster
Collection of characters for a saga.
- `characterId`: UUID
- `characterNumber`: number
- `name`: string
- `description`: string
- `createdAt`: ISO 8601 timestamp

### Chapter
Collection of frames for a saga (confusingly also called "roster" in some contexts).
- `chapterId`: UUID
- `chapterNumber`: number
- `name`: string
- `description`: string
- `createdAt`: ISO 8601 timestamp

### Frame
Individual comic panel/scene.
- `frameId`: UUID
- `chapterId`: UUID (owner - either chapter or character)
- `ownerType`: "saga" | "character"
- `frameNumber`: number
- `description`: string
- `imageUrl`: string (optional, data URL or blob URL)
- `status`: "draft" | "completed"
- `createdAt`: ISO 8601 timestamp

## State Response

All endpoints that modify state include a `revision` number. The `GET /state` endpoint returns the complete current state:

```json
{
  "chapterId": "uuid",
  "revision": number,
  "processing": boolean,
  "pendingCount": number,
  "sagaMeta": { "sagaId": "uuid", "name": string, "description": string },
  "saga": [chapters],
  "roster": [characters],
  "frames": [frames],
  "rosters": null,
  "sagas": null,
  "failed": [error objects]
}
```

## Endpoints

### State Management

#### GET `/api/state`
Get the current complete state.

**Response**: Full state object (see State Response above)

---

### Saga Management

#### POST `/api/add-saga`
Create a new saga.

**Body**:
```json
{
  "name": "string (required)",
  "description": "string"
}
```

**Response**:
```json
{
  "created": true,
  "saga": { "sagaId": "uuid", "name": "string", "description": "string" },
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/update-saga`
Update saga metadata (name and description).

**Body**:
```json
{
  "name": "string (required)",
  "description": "string"
}
```

**Response**: Full state object with updated `revision`

**Status**: 200 OK

---

#### POST `/api/delete-saga`
Delete a saga and all its chapters/frames.

**Body**:
```json
{
  "sagaId": "uuid (required)"
}
```

**Response**: Full state object with updated `revision`

**Status**: 200 OK

---

### Roster Management (Characters)

#### POST `/api/add-roster`
Create a new roster (character collection).

**Body**:
```json
{
  "name": "string (required)",
  "description": "string"
}
```

**Response**:
```json
{
  "created": true,
  "rosterEntity": { "characterId": "uuid", "characterNumber": number, "name": "string", ... },
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/add-character`
Add a character to a roster.

**Body**:
```json
{
  "rosterId": "uuid (required)",
  "name": "string (required)",
  "description": "string"
}
```

**Response**:
```json
{
  "created": true,
  "character": { "characterId": "uuid", "characterNumber": number, ... },
  "frame": { "frameId": "uuid", ... },
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/update-character`
Update a character's name and description.

**Body**:
```json
{
  "characterId": "uuid (required)",
  "name": "string (required)",
  "description": "string"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/delete-character`
Delete a character.

**Body**:
```json
{
  "characterId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

### Chapter Management

#### POST `/api/add-chapter`
Create a new chapter in a saga.

**Body**:
```json
{
  "sagaId": "uuid (required)",
  "rosterId": "uuid (required)",
  "name": "string (required)",
  "description": "string"
}
```

**Response**:
```json
{
  "created": true,
  "chapter": { "chapterId": "uuid", "chapterNumber": number, ... },
  "frame": { "frameId": "uuid", ... },
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/add-chapter-roster`
Associate a roster (character collection) with a chapter.

**Body**:
```json
{
  "chapterId": "uuid (required)",
  "rosterId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 201 Created

---

#### POST `/api/update-chapter`
Update a chapter's details.

**Body**:
```json
{
  "chapterId": "uuid (required)",
  "name": "string (required)",
  "description": "string"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/update-chapter-roster`
Update the roster associated with a chapter.

**Body**:
```json
{
  "chapterId": "uuid (required)",
  "rosterId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/delete-chapter`
Delete a chapter.

**Body**:
```json
{
  "chapterId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

### Frame Management

#### POST `/api/add-frame`
Add a new frame to a chapter or character.

**Body**:
```json
{
  "chapterId": "uuid (optional, defaults to chapter context)",
  "characterId": "uuid (optional, for character frames)",
  "ownerType": "saga|character (optional, defaults to 'saga')",
  "frameId": "uuid (optional, to specify frame ID)"
}
```

**Response**:
```json
{
  "created": true,
  "frame": { "frameId": "uuid", "frameNumber": number, ... },
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/add-uploaded-frames`
Add multiple frames with images (for batch uploads).

**Body**:
```json
{
  "chapterId": "uuid (required)",
  "imageDataUrls": ["data:image/...", "data:image/..."]
}
```

**Response**:
```json
{
  "created": true,
  "frames": [{ frameId, imageUrl, ... }, ...],
  "revision": number
}
```

**Status**: 201 Created

---

#### POST `/api/update-frame-description`
Update a frame's description.

**Body**:
```json
{
  "frameId": "uuid (required)",
  "description": "string (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/delete-frame`
Delete a frame.

**Body**:
```json
{
  "frameId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/delete-empty-frames`
Delete all frames without images in a chapter.

**Body**:
```json
{
  "chapterId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/clear-frame-image`
Remove the image from a frame.

**Body**:
```json
{
  "frameId": "uuid (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

#### POST `/api/replace-frame-image`
Replace a frame's image.

**Body**:
```json
{
  "frameId": "uuid (required)",
  "imageDataUrl": "data:image/... (required)"
}
```

**Response**: Full state object

**Status**: 200 OK

---

### Image Generation

#### POST `/api/generate-frame`
Queue a frame for AI image generation.

**Body**:
```json
{
  "frameId": "uuid (required)",
  "direction": "string (optional, generation prompt)",
  "withoutRoster": boolean (optional, whether to exclude character context)
}
```

**Response**: 
- `202 Accepted` if queued successfully
- Generates a `stateChanged` SignalR event when complete

**Errors**:
- 404: Frame not found
- 400: Frame not valid for generation

---

### SignalR / Realtime

#### POST `/api/negotiate`
Negotiate SignalR connection.

**Response**:
```json
{
  "url": "...",
  "accessToken": "..."
}
```

Used internally for realtime `stateChanged` events.

---

## Error Responses

All errors return JSON with an `error` field:

```json
{
  "error": "Human-readable error message"
}
```

**Common Status Codes**:
- `400 Bad Request`: Missing or invalid required fields
- `404 Not Found`: Referenced entity doesn't exist
- `500 Internal Server Error`: Server-side error (check Azure logs)

---

## Notes

1. **Optimistic Updates**: The frontend often updates UI immediately after a request, before receiving the response.
2. **Revision Numbers**: Always included in responses to track state versions.
3. **Image Data URLs**: Images can be passed as `data:image/...` URLs or stored blob URLs.
4. **Realtime Events**: Use SignalR for real-time updates instead of polling. Listen for `stateChanged` events.
5. **CORS**: All endpoints support CORS; `Access-Control-Allow-Origin` is set based on configuration.
