# Render Deployment Guide (Backend)

This guide explains how to deploy the GuardWork backend to Render.

## Architecture

- One Docker web service: `guardwork-backend`
- One managed Postgres database: `guardwork-postgres`
- Blueprint defined in `render.yaml`.

## Prerequisites

- Repository pushed to GitHub.
- Render account with access to the workspace.
- Docker not required locally (Render builds from `Dockerfile`).

## 1. Apply the Blueprint

1. Ensure `render.yaml` and `Dockerfile` are committed and pushed.
2. Open: `https://dashboard.render.com/blueprint/new?repo=<YOUR_REPO_URL>`
3. Complete Git OAuth if prompted.
4. Name the Blueprint and click **Apply**.
5. Render creates the web service and Postgres database.

## 2. Configure Environment Variables

Postgres connection vars are auto-wired from the database by the Blueprint:
`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.

Set these manually in the Render Dashboard (sync: false):

| Variable | Notes |
|---|---|
| `GUARDWORK_CORS_ALLOWED_ORIGINS` | Frontend origin(s), comma-separated (e.g. `https://app.example.com`) |
| `GUARDWORK_JWT_ACCESS_EXPIRATION_MS` | Optional (default 900000) |
| `CLOUDINARY_CLOUD_NAME` | If using CV uploads |
| `CLOUDINARY_API_KEY` | If using CV uploads |
| `CLOUDINARY_API_SECRET` | If using CV uploads |
| `COMPANY_INVITATION_*` | Email/invitation settings as needed |

## 3. Disable Auto-Deploy from Git

Disable auto-deploy in the service Settings so deploys only happen via the GitHub Actions workflow.

## 4. Configure GitHub Actions Secrets

Add repository secret:

- `RENDER_DEPLOY_HOOK_BACKEND` — from the service's Settings → Deploy Hook.

## 5. Deploy

- **Automatic:** publish a GitHub Release.
- **Manual:** Actions → "Deploy to Render" → Run workflow.

The workflow runs Maven tests (with a Postgres service container) and packages the jar, then triggers the Render deploy hook.

## 6. Verify

- Render Dashboard → service → Logs: confirm Docker build and Spring Boot startup.
- Check `http://<service-url>/health` returns `ok`.
- Frontend can reach the API at the backend URL.

## Local Development

```sh
cp .env.example .env
# edit .env (Postgres credentials, etc.)
# start Postgres
docker compose up -d postgres
./mvnw spring-boot:run
```
