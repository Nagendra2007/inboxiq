# Deploying Mailroom

Mailroom ships as **one Docker image** (the root `Dockerfile`): the React app is built and bundled into the Spring Boot jar, so the UI and the API are served from the same URL. Recommended free-tier stack:

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
git remote add origin https://github.com/<your-username>/mailroom.git
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
2. **New → Web Service** → choose the `mailroom` repository.
3. Render detects the `Dockerfile`. Set:
   - **Name:** anything, e.g. `mailroom` — your URL becomes `https://<name>.onrender.com` (Render adds a suffix if the name is taken)
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

Testing-mode limit: Google expires refresh tokens for Gmail scopes after **7 days**. When that happens Mailroom keeps you signed in and shows a **Reconnect Gmail** banner — click it and approve. Only a published (verified) app avoids this.

## 6. Use it

Open `https://<your-render-url>`, click **Continue with Google**, approve, and you land in your inbox while the newest 20 emails are fetched; summaries fill in as each is analyzed. You stay signed in — closing the browser or a redeploy doesn't sign you out (sessions are stored in the database). While the app is open, new Gmail messages appear on their own within about 30 seconds. After 15 idle minutes the free instance sleeps; the next visit shows "Waking up Mailroom…" and retries by itself, then catches up on only the mail that arrived meanwhile.

### Syncing while the app is closed

Mailroom also checks the mailboxes of users who are *away* every `GMAIL_BACKGROUND_POLL_MINUTES` (5 by default), so mail is fetched and analyzed as it arrives and the next sign-in has nothing left to do but read it out of the database. Checks stop for a mailbox nobody has opened in `GMAIL_BACKGROUND_ACTIVE_DAYS` (30), so an abandoned account stops spending Gmail quota and AI credits; signing in revives it.

**This needs a host that keeps the app running.** Render's free plan stops the instance after 15 minutes without a *visitor* — work the app does for itself doesn't count — so nothing can be synced while it's asleep, and the first visit afterwards waits a minute or more for it to start. If you want mail to keep arriving with the app closed, pick one of:

| Option | What it costs | Notes |
|---|---|---|
| Render **Starter** instance | ~$7/month | Nothing else changes; the service simply stops sleeping. |
| **Railway** | usage-based (a trial credit covers a small app) | Same image, no code changes. Containers don't sleep. |
| Stay free + an external uptime ping | free | A scheduler such as cron-job.org or UptimeRobot requesting `/actuator/health` every 10 minutes keeps the instance awake. Render's free plan allows 750 instance-hours a month and one always-on service uses about 730, so this fits — but only for a single free service, and always-on isn't what the free plan is meant for. |

Setting `GMAIL_BACKGROUND_POLL_MINUTES=0` turns background checks off, leaving sync to happen only while somebody has the app open.

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
| Mail only arrives while the app is open, and the first visit of the day takes a minute | The instance is asleep, so nothing runs in between — see [Syncing while the app is closed](#syncing-while-the-app-is-closed). |

## Deploying on AWS EC2

One `t4g.small` (2 GB, Graviton/ARM) running two containers: the app, and Caddy in front of it for TLS. The database stays on Neon, so the instance holds no state worth backing up and can be rebuilt from `deploy/` at any time. About $12/month on demand, in `ap-south-1` if you're in India.

Everything below assumes the files in [`deploy/`](deploy/).

### You need a domain first

Let's Encrypt will not issue a certificate for a bare IP, and Google will not accept an `http://` redirect URI, so there is no IP-only path to a working sign-in. A real domain is ~$10/year; a free `something.duckdns.org` works just as well and is on the Public Suffix List, so it gets its own certificate rate limit.

Point an A record at the instance's **Elastic IP** — allocate one and associate it, or the address changes on every stop/start and the certificate goes with it.

### 1. The instance

Launch `t4g.small`, Amazon Linux 2023 (arm64), 20 GB gp3. Security group: inbound `80` and `443` from anywhere, `22` from **your IP only**.

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user        # log out and back in
sudo dnf install -y docker-compose-plugin
```

### 2. The files

```bash
sudo install -d -o ec2-user -g ec2-user /opt/mailroom
cd /opt/mailroom
curl -O https://raw.githubusercontent.com/<you>/mailroom/main/deploy/docker-compose.yml
curl -O https://raw.githubusercontent.com/<you>/mailroom/main/deploy/Caddyfile
echo "MAILROOM_DOMAIN=mail.example.com" > .env
```

Then write `app.env` from [`deploy/app.env.example`](deploy/app.env.example) and `chmod 600 app.env` — it holds the keys to a Gmail mailbox.

Two values must come across from Render unchanged:

- **`TOKEN_ENCRYPTION_KEY`** — the AES-256-GCM key for stored Gmail tokens. A new key makes every token in the database undecryptable and forces every user to reconnect.
- **`DATABASE_URL`** and its credentials, if you want to keep the mail that's already synced.

And three must be set explicitly, because nothing on EC2 injects `RENDER_EXTERNAL_URL`: `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `GOOGLE_REDIRECT_URI`. Left unset they fall back to `http://localhost:5173` and sign-in fails as a Google `redirect_uri_mismatch`.

### 3. First run

```bash
cd /opt/mailroom && docker compose up -d
docker compose logs -f caddy      # watch the certificate being issued
curl -sS https://mail.example.com/actuator/health
```

### 4. Tell Google

Add `https://mail.example.com/login/oauth2/code/google` to **Google Cloud Console → Credentials → your OAuth client → Authorised redirect URIs**. Until you do, sign-in fails.

### 5. Automatic deploys

[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) builds the image on an arm64 runner, pushes it to GHCR and restarts the container over SSH. Set these in the repository:

| | Name | Value |
|---|---|---|
| Variable | `EC2_HOST` | the Elastic IP or hostname |
| Variable | `EC2_USER` | `ec2-user` |
| Variable | `MAILROOM_DOMAIN` | `mail.example.com` |
| Secret | `EC2_SSH_KEY` | the private key for that instance |

The rollout step is skipped while `EC2_HOST` is unset, so the workflow is safe to merge before the instance exists. Each deploy pins the image to the commit SHA, so a rollback is re-running an older commit's workflow.

### Notes

- **Delete `.github/workflows/keep-warm.yml` once you cut over.** Nothing spins down here, so the ping is pointless — and the background sync finally runs unattended, which was the whole point of it.
- `flush_interval -1` in the Caddyfile is not optional. Without it Caddy buffers responses and `/api/events` never reaches the browser, so live updates silently stop working.
- Keep `mem_limit` on the app container. The JVM sizes its heap from the container limit, and without one it takes 70% of the whole host and starves Caddy.
- Certificates live in the `caddy_data` volume. Don't delete it casually — Let's Encrypt rate-limits re-issuing.

## Other hosts

The image runs anywhere Docker does (Railway, Fly.io, a VPS, Kubernetes). It honours the platform's `PORT` variable and trusts `X-Forwarded-*` headers from private-network proxies, so it works behind a TLS-terminating load balancer:

```bash
docker build -t mailroom .
docker run -p 8080:8080 --env-file .env.render -e FRONTEND_URL=https://your.domain -e CORS_ALLOWED_ORIGINS=https://your.domain -e GOOGLE_REDIRECT_URI=https://your.domain/login/oauth2/code/google mailroom
```

Outside Render, set those three URL variables to your public URL explicitly.

## Scaling notes

- Sessions are stored in Postgres (Spring Session JDBC), so restarts, redeploys and extra instances don't sign anyone out.
- Rate limiting, the live-update streams and sync coordination are in-memory, so they're per instance. Running more than one instance needs a shared store (e.g. Redis, or Postgres `LISTEN/NOTIFY` for events) behind `RateLimiterService`, `EventStreamService` and `SyncCoordinator`.

Optional tuning variables (defaults in brackets): `SESSION_DAYS` [30], `FIRST_SYNC_MESSAGE_CAP` [20], `GMAIL_POLL_SECONDS` [30], `GMAIL_BACKGROUND_POLL_MINUTES` [5], `GMAIL_BACKGROUND_ACTIVE_DAYS` [30], `MAX_NEW_MESSAGES_PER_SYNC` [100], `AI_ANALYSIS_CONCURRENCY` [2].
