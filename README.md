# RoboGene

RoboGene is a ClojureScript web app + Azure Functions backend for comic frame generation.

## Domain Concepts

- **Roster**: The long-term memory for a character's visual appearance. It is used purely during image generation to ensure the correct visuals are produced when generating a new frame. A story or chapter is typically linked to a roster, but individual frames can utilize "visiting rosters" to allow characters from other stories to appear. The linking feature is currently unfinished.

## What Is Special Here

- Realtime updates use **Azure SignalR** (`stateChanged` events).
- The frontend does **not** use scheduled polling for normal state sync.
- The intended architecture is push-first realtime sync plus incremental loading, not full-database browser loads.
- Secrets are handled via `.env` files locally and Azure App Settings in production.
- Deploy to Azure is automatic from GitHub Actions on `main`.

Architecture guidance for future changes:
- [ARCHITECTURE.md](ARCHITECTURE.md)

## Local Secrets + Run (Single Flow)

Use one env mechanism for everything (app + tests): shell env files.

Copy-paste:

```bash
cp robogen.env.example robogen.env

# Fill real secrets in robogen.env:
# - ROBOGENE_IMAGE_GENERATOR_KEY (or whatever env names your IMAGE_GENERATORS entries reference)
# - AzureWebJobsStorage (or ROBOGENE_STORAGE_CONNECTION_STRING)
# - AzureSignalRConnectionString

npm install
npm start
```

## Tooling

Required:
- Node.js 22+ and npm
- Java (required by `shadow-cljs`)
- Clojure CLI (`clojure`) required by `shadow-cljs`
- Azure Functions Core Tools v4 (`func`)

Only for release mode:
- Python 3 (static webapp server)

Note:
- Using `az` and `gh`, it is possible to regenerate a working `.env` file.

Run UI E2E with same env mechanism:

```bash
npm run test:e2e:ui:env
```

Notes:
- `robogen.env` holds real secrets.
- `robogen-test.env` is tracked in the repo and provides the test overlay (ports/timeouts/mock generator).
- `local.settings.json` is not the source of truth in this project.

## CI/CD

Deploy workflow:
- `.github/workflows/deploy.yml`

On push to `main`, GitHub Actions builds and deploys:
- Azure Functions (webapi)
- GitHub Pages (webapp)

## Azure Setup (Short)

You need:
- Function App (Node 22 / Functions v4)
- Storage account
- SignalR Service
- App settings with required secrets (provider API keys referenced by `src/host/host.json`, storage, SignalR)

Full hosting + CLI deploy guide:
- [Azure hosting guide](scripts/azure/HOSTING.md)

## Database

- Azure Table Storage table: `robogeneEntities`
- Partition key: workspace id, currently `default`
- Row key: entity id
- Each row stores one normalized entity in `payloadJson`
- Images are stored in Blob Storage container `robogene-images`
- Old tables from before the flat entity model are obsolete

## Monitoring

- Azure dashboard (direct): [Application Insights - robogene-func-prod](https://portal.azure.com/#@hbceducation.onmicrosoft.com/resource/subscriptions/aaa0b596-1388-40cf-a166-cbbf5731a57f/resourceGroups/robogene-rg/providers/microsoft.insights/components/robogene-func-prod/overview)
- CLI summary: `./scripts/azure/monitor_summary.sh`
- Optional: `./scripts/azure/monitor_summary.sh robogene-func-prod robogene-rg 6h`

## API Documentation

Complete API reference with all endpoints, request/response formats, and examples:
- [API.md](API.md)

## Contributing

Project priorities for all contributors:
- Prefer clean, elegant, declarative code over preserving existing behavior.
- Redesign or remove features when needed to improve structure and readability.
- Reduce duplication and reuse existing components before creating new ones.
- Favor dumb functions, single responsibility, and clear separation of concerns.

Agent-specific working rules are documented in:
- [AGENTS.md](AGENTS.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
