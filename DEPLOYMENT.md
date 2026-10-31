# Deploying Mailroom

Mailroom ships as **one Docker image** (the root `Dockerfile`): the React app is built and bundled into the Spring Boot jar, so the UI and the API are served from the same URL.

| Piece | Service | Why |
|---|---|---|
| App (UI + API) | **EC2** `t4g.small`, Docker, Caddy in front | Always on, no cold start, and no proxy timeout on the live-update stream. ~$12/month. |
| Database | **RDS** `db.t4g.micro`, private | Same VPC and region as the app, so a query is a round trip of about a millisecond. 7-day automated backups. |
| TLS | **Caddy** + Let's Encrypt | Certificates issued and renewed with no cron job and no config. |
| Images | **GHCR**, built by GitHub Actions | Free for public repositories, and arm64 is built natively. |

Everything goes in **`ap-south-1` (Mumbai)**. Co-locating the database with the app is worth more than any instance size.

## Why one origin (and not Vercel + an API host)

The session and CSRF cookies only work reliably when the browser sees the UI and API as the **same site**:

- The SPA reads the `XSRF-TOKEN` cookie with JavaScript and echoes it back in a header. JavaScript on `app.vercel.app` **cannot read a cookie set by `api.example.com`**, so every POST/DELETE would fail with 403.
- Safari and Firefox block (or partition) third-party cookies, so a session cookie from a different site never comes back — sign-in silently fails for those users even with `SameSite=None`.

Serving both from one origin makes the cookies first-party: no CORS, no `SameSite=None`, and it works in every browser. It's also one thing to deploy instead of two.

## 1. A domain

Let's Encrypt will not issue a certificate for a bare IP, and Google will not accept an `http://` redirect URI, so there is no IP-only path to a working sign-in.

A real domain is ~$10/year. A free `something.duckdns.org` works just as well and is on the Public Suffix List, so it gets its own certificate rate limit. Point its A record at the **Elastic IP** from the next step — allocate one and associate it, or the address changes on every stop/start and the certificate goes with it.

## 2. The AWS pieces

Create the security groups **first**, so the instance and the database can reference each other.

| Group | Inbound |
|---|---|
| `mailroom-app` | TCP 80 and 443 from anywhere, **UDP 443** from anywhere, TCP 22 from **your IP only** |
| `mailroom-db` | TCP 5432, source = the **`mailroom-app` security group** (the group, not an IP range) |

That UDP rule is what enables HTTP/3. Caddy serves it by default and QUIC holds up better than TCP on a patchy mobile connection, but without the rule the browser quietly falls back to HTTP/2 and nothing says so. Referencing the app's *group* in the database rule means it keeps working when the instance changes address.

**EC2:** `t4g.small`, Amazon Linux 2023 (**arm64**, not x86), 20 GB gp3, security group `mailroom-app`. Create a key pair and keep the `.pem`.

**RDS:** PostgreSQL, `db.t4g.micro`, 20 GB gp3 with storage autoscaling, **Single-AZ**, security group `mailroom-db`, **Public access: No**. Take the latest major version — starting empty, there is nothing to match.

> **Set *Initial database name* to `mailroom`.** Leave it blank and RDS creates no database at all, and the app fails on first boot with a connection error that never mentions it.

Automated backups default to 7 days and are free up to the size of the database. That retention is most of what RDS buys you over a Postgres container, so don't turn it off.

### Starting fresh, or bringing data across

Starting empty is the recommended path: Flyway builds the schema on first boot, you sign in, Gmail reconnects, and the newest `FIRST_SYNC_MESSAGE_CAP` messages sync. Generate `TOKEN_ENCRYPTION_KEY` with `openssl rand -base64 32` — with no rows to decrypt, nothing has to match anything.

To migrate from an existing database instead, run this **from the EC2 instance**, the only machine that can reach both ends:

```bash
sudo dnf install -y postgresql16
pg_dump "postgresql://<old-user>:<pw>@<old-host>/<db>?sslmode=require" -Fc -f mailroom.dump
pg_restore --no-owner --no-privileges \
  -d "postgresql://mailroom:<pw>@<rds-endpoint>:5432/mailroom?sslmode=require" mailroom.dump
```

The dump includes `flyway_schema_history`, so Flyway sees every migration as applied and validates instead of re-running them. **`TOKEN_ENCRYPTION_KEY` must then come across unchanged** — the `mail_accounts` rows hold Gmail refresh tokens encrypted with it, and a new key turns all of them into undecryptable bytes, forcing the reconnect you were migrating to avoid.

## 3. The files on the instance

```bash
sudo dnf install -y docker docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user      # log out and back in
```

```bash
sudo install -d -o ec2-user -g ec2-user /opt/mailroom
cd /opt/mailroom
curl -O https://raw.githubusercontent.com/<you>/mailroom/main/deploy/docker-compose.yml
curl -O https://raw.githubusercontent.com/<you>/mailroom/main/deploy/Caddyfile
echo "MAILROOM_DOMAIN=mail.example.com" > .env
```

Then write `app.env` from [`deploy/app.env.example`](deploy/app.env.example) and `chmod 600 app.env` — it holds the keys to a Gmail mailbox.

Three variables the app cannot work out for itself: `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` and `GOOGLE_REDIRECT_URI`, all set to your public https URL. Left unset they fall back to `http://localhost:5173`, and sign-in then fails as a Google `redirect_uri_mismatch` that looks like a Google problem.

## 4. First run

```bash
cd /opt/mailroom && docker compose up -d
docker compose logs -f caddy      # watch the certificate being issued
curl -sS https://mail.example.com/actuator/health
```

If DNS hasn't propagated yet, Caddy retries on its own.

## 5. Tell Google about the URL

In Google Cloud Console, under **APIs & Services**:

1. **Credentials** → your OAuth client → **Authorized redirect URIs** → add `https://mail.example.com/login/oauth2/code/google` → **Save**. Keep the `http://localhost:8080/...` entry for local use.
2. **OAuth consent screen → Test users:** make sure your Gmail address is listed. While the app is in Testing mode only listed accounts can sign in.
3. Scopes should include `gmail.readonly`, `gmail.send` and `gmail.modify` (plus `openid`, `email`, `profile`).

Testing-mode limit: Google expires refresh tokens for Gmail scopes after **7 days**. When that happens Mailroom keeps you signed in and shows a **Reconnect Gmail** banner — click it and approve. Only a published (verified) app avoids this.

## 6. Automatic deploys

[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) builds the image on an arm64 runner, pushes it to GHCR and restarts the container over SSH. Set these under **Settings → Secrets and variables → Actions**:

| | Name | Value |
|---|---|---|
| Variable | `EC2_HOST` | the Elastic IP or hostname |
| Variable | `EC2_USER` | `ec2-user` |
| Variable | `MAILROOM_DOMAIN` | `mail.example.com` |
| Secret | `EC2_SSH_KEY` | the private key for that instance |

The rollout step is skipped while `EC2_HOST` is unset, so the workflow is safe to merge before the instance exists. Each deploy pins the image to the commit SHA, so a rollback is re-running an older commit's workflow.

**Check the GHCR package is public** after the first build — your profile → **Packages** → `mailroom` → **Package settings**. If it is private the instance gets `denied` on `docker compose pull`, and the error does not say why.

## Using it

Open `https://mail.example.com`, click **Continue with Google**, approve, and you land in your inbox while the newest 20 emails are fetched; summaries fill in as each is analyzed. You stay signed in — closing the browser or a redeploy doesn't sign you out, since sessions are stored in the database. While the app is open, new Gmail messages appear on their own within about 30 seconds.

### Syncing while the app is closed

Mailroom also checks the mailboxes of users who are *away* every `GMAIL_BACKGROUND_POLL_MINUTES` (5 by default), so mail is fetched and analyzed as it arrives and the next sign-in has nothing left to do but read it out of the database. Checks stop for a mailbox nobody has opened in `GMAIL_BACKGROUND_ACTIVE_DAYS` (30), so an abandoned account stops spending Gmail quota and AI credits; signing in revives it.

This only works on a host that keeps the app running between visits, which is one of the reasons for a plain instance rather than a scale-to-zero platform. Setting `GMAIL_BACKGROUND_POLL_MINUTES=0` turns background checks off, leaving sync to happen only while somebody has the app open.

## Changing the AI provider, model or key

No redeploy needed. Signed in as the administrator, open **Settings → AI provider**:

1. Pick the provider — OpenRouter, OpenAI, Google Gemini, Anthropic (Claude), Groq, DeepSeek, Mistral, or **Custom** for any other OpenAI-compatible endpoint.
2. Type the model (suggestions are offered; any model the provider supports works).
3. Paste the API key — or leave it blank to keep the saved key (or, for the same provider, the environment's).
4. **Test connection**, then **Save changes**. Everyone's summaries and drafts use it from the next request. **Reset to defaults** returns to the `AI_*` environment variables.

Keys saved here are encrypted with `TOKEN_ENCRYPTION_KEY` and never sent back to the browser (only their last four characters).

**Who is the administrator?** The accounts listed in `ADMIN_EMAILS` (comma-separated). If that isn't set, the first account that signed in to this deployment is the administrator.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Google shows `redirect_uri_mismatch` | The redirect URI in Google Cloud doesn't exactly match `https://<your-domain>/login/oauth2/code/google`, or `GOOGLE_REDIRECT_URI` is unset and fell back to localhost. |
| "Access blocked: app has not completed verification" | Your Google account isn't in the consent screen's **Test users**. |
| After Google sign-in you're back on the sign-in page | `FRONTEND_URL` / `CORS_ALLOWED_ORIGINS` don't match the domain you're actually using. |
| `docker compose pull` says `denied` | The GHCR package is private — make it public, or `docker login ghcr.io` on the instance with a PAT. |
| Caddy never gets a certificate | DNS isn't pointing at the Elastic IP yet, or TCP 80 is closed. Let's Encrypt needs port 80 reachable even for an https-only site. |
| App crashes on start: database connection | RDS *Initial database name* was left blank, or `mailroom-db` doesn't allow the app's security group on 5432. |
| App crashes on start: *TOKEN_ENCRYPTION_KEY* | Missing, or not Base64 of 16/24/32 bytes — `openssl rand -base64 32`. |
| Everyone has to reconnect Gmail after a migration | `TOKEN_ENCRYPTION_KEY` changed, so the stored refresh tokens can't be decrypted. |
| AI summaries never appear, or "out of credits" / "rejected the API key" | Open **Settings → AI provider → Test connection** — it shows the provider's own explanation. Failed analyses are retried a couple of times; **Re-analyze** on an email retries it now. |
| New mail doesn't appear by itself (the dot beside **Inbox** stays amber) | The live connection (`/api/events`) is being buffered or blocked. Check `flush_interval -1` is still in the Caddyfile — without it the stream never reaches the browser. |
| Out-of-memory restarts | Lower `mem_limit` in `deploy/docker-compose.yml`, or move to `t4g.medium`. The JVM sizes its heap from the container limit, so it must have one. |

## Other hosts

The image runs anywhere Docker does (Railway, Fly.io, App Runner, another VPS, Kubernetes). It honours the platform's `PORT` variable and trusts `X-Forwarded-*` headers from private-network proxies, so it works behind a TLS-terminating load balancer:

```bash
docker build -t mailroom .
docker run -p 8080:8080 --env-file app.env mailroom
```

Wherever it runs, set `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` and `GOOGLE_REDIRECT_URI` to the public URL explicitly. One thing to check on a managed platform: many cap request duration (App Runner at 120 seconds), which cuts the `/api/events` stream. The app reconnects on its own, so it keeps working — it just reconnects that often.

## Scaling notes

- Sessions are stored in Postgres (Spring Session JDBC), so restarts, redeploys and extra instances don't sign anyone out.
- Rate limiting, the live-update streams and sync coordination are in-memory, so they're per instance. Running more than one instance needs a shared store (e.g. Redis, or Postgres `LISTEN/NOTIFY` for events) behind `RateLimiterService`, `EventStreamService` and `SyncCoordinator`.

Optional tuning variables (defaults in brackets): `SESSION_DAYS` [30], `FIRST_SYNC_MESSAGE_CAP` [20], `GMAIL_POLL_SECONDS` [30], `GMAIL_BACKGROUND_POLL_MINUTES` [5], `GMAIL_BACKGROUND_ACTIVE_DAYS` [30], `MAX_NEW_MESSAGES_PER_SYNC` [100], `AI_ANALYSIS_CONCURRENCY` [2].
