# Deploying InboxIQ

InboxIQ ships as **one Docker image** (the root `Dockerfile`): the React app is built and bundled into the Spring Boot jar, so the UI and the API are served from the same URL. Recommended free-tier stack:

| Piece | Service | Why |
|---|---|---|
| App (UI + API) | **Render** web service (Docker) | Builds the root `Dockerfile` directly. Free instances sleep after 15 min idle and take a minute or two to wake. |
| Database | **Neon** Postgres | Free, non-expiring (0.5 GB). Render's free Postgres is deleted after 30 days. |

On Render the app works out its own public address from `RENDER_EXTERNAL_URL` (set by Render), so the Google redirect URI, the post-login redirect and the allowed origin need no configuration.

## Why one origin (and not Vercel + Render)

The session and CSRF cookies only work reliably when the browser sees the UI and API as the **same site**:

- The SPA reads the `XSRF-TOKEN` cookie with JavaScript and echoes it back in a header. JavaScript on `app.vercel.app` **cannot read a cookie set by `api.onrender.com`**, so every POST/DELETE would fail with 403.
- Safari and Firefox block (or partition) third-party cookies, so a session cookie from a different site never comes back — sign-in silently fails for those users even with `SameSite=None`.

Serving both from one origin makes the cookies first-party: no CORS, no `SameSite=None`, and it works in every browser. It's also one service to deploy instead of two.

## 1. Push the project to GitHub

Create an empty **private** repository on GitHub (no README), then from the repo root:

```bash
git init -b main
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/<your-username>/inboxiq.git
git push -u origin main
```

`.env` files are git-ignored, so no secrets are committed — `git status` should never list one.

## 2. Create the database (Neon)

1. Sign up at [neon.com](https://neon.com) and create a project in a region near your Render region.
2. Open **Connect**, switch **Connection pooling off**, and copy the connection string (`postgresql://user:password@host/neondb?sslmode=require`).

No manual migration is needed — Flyway creates the schema on first startup.

## 3. Prepare the environment variables

With the Neon connection string still on your clipboard, run from the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\prepare-render-env.ps1
```

It reads the Neon string from the clipboard (or asks for it, with hidden input). The script:

- converts it to `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` (switching a pooled host to the direct one),
- copies `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and the AI defaults (`AI_PROVIDER`, `AI_API_KEY`, `AI_MODEL`) from `backend/.env`,
- generates a production `TOKEN_ENCRYPTION_KEY` (once — later runs reuse it; changing it would force every user to reconnect Gmail),
- adds `COOKIE_SECURE=true` and `COOKIE_SAME_SITE=Lax`,
- saves everything to `.env.render` (git-ignored) and **copies it to your clipboard**. No secret is printed.

<details>
<summary>Doing it by hand instead</summary>

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host>/<dbname>?sslmode=require` (from the Neon string) |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | From the Neon string |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Same as `backend/.env` |
| `AI_PROVIDER`, `AI_API_KEY`, `AI_MODEL` | e.g. `openrouter`, your OpenRouter key, `openai/gpt-4o-mini` (changeable later in the app) |
| `TOKEN_ENCRYPTION_KEY` | `openssl rand -base64 32`, or in PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)` |
| `COOKIE_SECURE` | `true` |

</details>

## 4. Deploy on Render

1. Sign up at [render.com](https://render.com) with your GitHub account.
2. **New → Web Service** → choose the `inboxiq` repository.
3. Render detects the `Dockerfile`. Set:
   - **Name:** anything, e.g. `inboxiq` — your URL becomes `https://<name>.onrender.com` (Render adds a suffix if the name is taken)
   - **Instance type:** Free
   - **Environment Variables:** click **Add from .env** and paste (your clipboard from step 3)
   - **Advanced → Health Check Path:** `/actuator/health`
4. **Deploy Web Service.** The first build takes about 10 minutes. Wait for **Live**, then note the exact URL at the top of the page.

(`render.yaml` in the repo describes the same service as a Blueprint, if you prefer **New → Blueprint** — it prompts for each secret individually instead.)

## 5. Tell Google about the new URL

In Google Cloud Console → **APIs & Services**:

1. **Credentials →** your OAuth client → **Authorized redirect URIs** → add `https://<your-render-url>/login/oauth2/code/google` → **Save**. Keep the `http://localhost:8080/...` entry for local use.
2. **OAuth consent screen → Test users:** make sure your Gmail address is listed. While the app is in Testing mode only listed accounts can sign in.
3. Scopes on the consent screen should include `gmail.readonly`, `gmail.send` and `gmail.modify` (plus `openid`, `email`, `profile`).

Testing-mode limit: Google expires refresh tokens for Gmail scopes after **7 days**. When that happens InboxIQ keeps you signed in and shows a **Reconnect Gmail** banner — click it and approve. Only a published (verified) app avoids this.

## 6. Use it

Open `https://<your-render-url>`, click **Continue with Google**, approve, and you land in your inbox while the newest 20 emails are fetched; summaries fill in as each is analyzed. You stay signed in — closing the browser or a redeploy doesn't sign you out (sessions are stored in the database). While the app is open, new Gmail messages appear on their own within about 30 seconds. After 15 idle minutes the free instance sleeps; the next visit shows "Waking up InboxIQ…" and retries by itself, then catches up on only the mail that arrived meanwhile.

## Changing the AI provider, model or key

No redeploy needed. Signed in as the administrator, open **Settings → AI provider**:

1. Pick the provider — OpenRouter, OpenAI, Google Gemini, Anthropic (Claude), Groq, DeepSeek, Mistral, or **Custom** for any other OpenAI-compatible endpoint.
2. Type the model (suggestions are offered; any model the provider supports works).
3. Paste the API key — or leave it blank to keep the saved key (or, for the same provider, the environment's).
4. **Test connection**, then **Save changes**. Everyone's summaries and drafts use it from the next request. **Reset to defaults** returns to the `AI_*` environment variables.

Keys saved here are encrypted with `TOKEN_ENCRYPTION_KEY` and never sent back to the browser (only their last four characters).

**Who is the administrator?** The accounts listed in `ADMIN_EMAILS` (comma-separated). If that isn't set, the first account that signed in to this deployment is the administrator. Set `ADMIN_EMAILS` if you want to pin it or add someone.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Google shows `redirect_uri_mismatch` | The redirect URI in Google Cloud doesn't exactly match `https://<your-render-url>/login/oauth2/code/google` (check the Render suffix; no trailing slash). |
| "Access blocked: app has not completed verification" | Your Google account isn't in the consent screen's **Test users**. |
| After Google sign-in you're back on the sign-in page | A custom domain is in use: set `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` and `GOOGLE_REDIRECT_URI` to it explicitly. |
| App crashes on start: *TOKEN_ENCRYPTION_KEY* | Missing, or not Base64 of 16/24/32 bytes — rerun the script from step 3. |
| App crashes on start: database connection | Wrong `DATABASE_*` values, or the pooled Neon host — rerun step 3 with the direct connection string. |
| AI summaries never appear, or "out of credits" / "rejected the API key" | Open **Settings → AI provider → Test connection** — it shows the provider's own explanation. Top up credits, fix the key, or switch provider/model there. Failed analyses are retried automatically a couple of times; **Re-analyze** on an email retries it now. |
| New mail doesn't appear by itself (the dot beside **Inbox** stays amber) | Something between the browser and the app is blocking the live connection (`/api/events`). The inbox still works — use **Sync** — and it reconnects on its own. |
| Out-of-memory restarts on the free plan | Add `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`. |

## Other hosts

The image runs anywhere Docker does (Railway, Fly.io, a VPS, Kubernetes). It honours the platform's `PORT` variable and trusts `X-Forwarded-*` headers from private-network proxies, so it works behind a TLS-terminating load balancer:

```bash
docker build -t inboxiq .
docker run -p 8080:8080 --env-file .env.render -e FRONTEND_URL=https://your.domain -e CORS_ALLOWED_ORIGINS=https://your.domain -e GOOGLE_REDIRECT_URI=https://your.domain/login/oauth2/code/google inboxiq
```

Outside Render, set those three URL variables to your public URL explicitly.

## Scaling notes

- Sessions are stored in Postgres (Spring Session JDBC), so restarts, redeploys and extra instances don't sign anyone out.
- Rate limiting, the live-update streams and sync coordination are in-memory, so they're per instance. Running more than one instance needs a shared store (e.g. Redis, or Postgres `LISTEN/NOTIFY` for events) behind `RateLimiterService`, `EventStreamService` and `SyncCoordinator`.

Optional tuning variables (defaults in brackets): `SESSION_DAYS` [30], `FIRST_SYNC_MESSAGE_CAP` [20], `GMAIL_POLL_SECONDS` [30], `MAX_NEW_MESSAGES_PER_SYNC` [100], `AI_ANALYSIS_CONCURRENCY` [2].
