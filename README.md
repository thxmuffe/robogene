# RoboGene

Interactive story-to-image web app with continuity memory.

## What it does
- No input fields in UI: one `Generate Next` button only.
- Auto-loads Episode 28 storyboard/prompt pack on server startup.
- Uses page 1 as a fixed reference frame in the gallery.
- Loads existing generated frames from `robogene/generated/`.
- `Generate Next` creates exactly the next scene and inserts it as most recent in the gallery.
- Keeps memory of previous scenes and includes continuity in prompts.

## Run
From `/Users/penpa/Desktop/PDFs`:

```bash
set -a; source pop.env; set +a
node robogene/backend/server.js
```

Then open: `http://localhost:8787`

## Notes
- Frontend files are in repo root: `index.html`, `app.js`, `styles.css`.
- Backend files are in `backend/`.
- For hosted frontend, set `window.ROBOGENE_API_BASE` in `index.html` to your backend origin.
- API key must be available as `OPENAI_API_KEY`.
- Default model: `gpt-image-1`.
- Default reference image: `robogene/references/robot_emperor_ep22_p01.png`.
- Generated images are written to `robogene/generated/`.
- Existing files `scene_02.png`, `scene_03.png`, etc. are reused on startup.
- The app continues from the highest existing scene number.
