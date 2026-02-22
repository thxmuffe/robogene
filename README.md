# RoboGene

RoboGene is a ClojureScript web app + Azure Functions backend for comic frame generation.

## What Is Special Here

- Realtime updates use **Azure SignalR** (`stateChanged` events).
- The frontend does **not** use scheduled polling for normal state sync.
- Secrets are handled via `.env` files locally and Azure App Settings in production.
- Deploy to Azure is automatic from GitHub Actions on `main`.

Example image in repo:
- [Reference frame PNG](ai/robot%20emperor/references/robot_emperor_ep22_p01.png)

## Local Secrets + Run (Single Flow)

Use one env mechanism for everything (app + tests): shell env files.

Copy-paste:

```bash
cp robogen.debug.env.example robogen.debug.env
cp robogen.test.env.example robogen.test.env

# Fill real secrets in robogen.debug.env:
# - OPENAI_API_KEY
# - AzureWebJobsStorage (or ROBOGENE_STORAGE_CONNECTION_STRING)
# - AzureSignalRConnectionString

npm install
npm start
```

Run UI E2E with same env mechanism:

```bash
npm run test:e2e:ui:env
```

Notes:
- `robogen.debug.env` holds real secrets.
- `robogen.test.env` is only an overlay (ports/timeouts), no secrets.
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
- App settings with required secrets (`OPENAI_API_KEY`, storage, SignalR)

Full hosting + CLI deploy guide:
- [HOSTING.md](HOSTING.md)
