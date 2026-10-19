# Widget — SleepWake webapp

Laravel 13 + Blade + MySQL. Receives sleep data from the Android app (Gadgetbridge fork) and shows it as graphs.
Single owner, personal use. Theme: "Morning Pastel" (light colors, from the personal-gallery project).

Differences from the spec (on purpose, to keep it small):

- **No Vite / npm.** Chart.js 4 is vendored in `public/vendor/chart.umd.js` (no CDN). The charts use a plain numeric time axis, so no date adapter is needed.
- **No Breeze / Sanctum.** Owner login uses Laravel's own auth. API tokens are in the `api_tokens` table, stored as sha256 hashes, with an `ingest` ability and revoke.
- No queue worker is needed. Only the Laravel scheduler (one cron line) for the daily cleanup.

## Local setup

```bash
composer install
cp .env.example .env && php artisan key:generate
touch database/database.sqlite
php artisan migrate --seed          # seed = 14 fake nights (never on production)
printf 'a-long-password\n' | php artisan widget:user me@example.com
php artisan serve
php artisan test
```

You can also open `/register` once to make the owner. It is closed after the first user exists.

## `.env` keys

| Key | Meaning |
|---|---|
| `APP_URL` | public HTTPS URL |
| `APP_ENV=production`, `APP_DEBUG=false` | production values |
| `DB_CONNECTION=mysql`, `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD` | database |
| `SESSION_DRIVER=database` | sessions in DB |

Everything else (time zone, cleanup days) is on the **Settings** page and stored in the DB.

## Artisan commands

| Command | What it does |
|---|---|
| `widget:user {email}` | create/update the owner (password from stdin) |
| `widget:token {name}` | create an API token (shown once). `--revoke=ID` revokes one |
| `widget:cleanup` | delete old data and logs now. `--data-days=N --log-days=N` override the settings |

Tokens can also be created and revoked on the Settings page.

## Cleanup (auto-delete)

On the **Settings** page you set two numbers of days (default **90** each, `0` = keep forever):

- **Data**: samples, events and nights older than N days.
- **Logs**: the ingest request log and `storage/logs/*.log` files older than N days.

`php artisan schedule:run` (cron, every minute) runs `widget:cleanup` every day at 03:30. There is also a "Clean up now" button.

## API (v1, `Authorization: Bearer <token>`)

- `GET /api/v1/ping` → `{"ok":true,"server_time":"..."}`
- `POST /api/v1/ingest` → one idempotent batch. Body: `device`, `sessions[]`, `samples[]`, `events[]` as in the project spec
  (`reference-docs/SLEEPWAKE_PROJECT_SPEC.md`, section 4.3). Limits: 5000 samples and 5000 events per request, 200 sessions, 120 requests/minute.
  Response: `{"ok":true,"accepted":{"sessions":1,"samples":1,"events":1},"duplicates":0}`.
- Samples: unique `(device, ts)`. Events: unique `(session, ts, type)`. Re-sending is safe. Late samples are fine (they are matched to a night at read time).
- Events whose `session_uuid` is unknown are skipped, so send the session in the same or an earlier batch.
- `morning_rating` events set the rating/notes of the night. `wake_triggered` events fill `wake_reason` if empty.
- Errors: `401` bad token, `422` validation (JSON `details`), `429` rate limit.

Test:

```bash
curl -H "Authorization: Bearer sw_..." https://widget.peppasoft.com/api/v1/ping
```

## Pages

Nights list (date filter) · Night detail (HR + movement charts, wake window band, event markers, events table, rating and notes) ·
Trends (week / month / custom) · Export (CSV or JSON, combined or one file per night; columns are listed on the page) ·
Settings (time zone, cleanup days, tokens, devices, last ingest requests).

## Production (ger1, `widget.peppasoft.com`)

| Fact | Value |
|---|---|
| Virtual server / user | `widget.peppasoft.com` / `widget` (Virtualmin), Cloudflare in front |
| App | `/mnt/ger_hd1/www/widget/app` (mode 710), docroot `app/public` |
| PHP | FPM pool, PHP 8.4 |
| Database | MySQL `widget`, user `widget`, random password (only in `app/.env` and `/root/.widget-db-pass` on the server) |
| Cron | `/etc/cron.d/widget` → `schedule:run` every minute |
| Health | `https://widget.peppasoft.com/up` → 200 |

One-time setup (already done):

```bash
virtualmin enable-feature --domain widget.peppasoft.com --mysql
virtualmin modify-web --domain widget.peppasoft.com --mode fpm
virtualmin modify-web --domain widget.peppasoft.com --php-version 8.4      # separate call
virtualmin modify-web --domain widget.peppasoft.com --document-dir app/public
virtualmin modify-database-pass --domain widget.peppasoft.com --type mysql --pass '<random>'
```

Update: commit, then `bash deploy/deploy.sh` from the repo root. It backs up the app and DB, keeps `.env` and `storage/`,
runs `composer install --no-dev`, `migrate`, `optimize`, flushes this pool's opcache and checks `/up`.
`.md` files are never copied to the server. Apache strips the `Authorization` header unless `public/.htaccess` passes it on (the Laravel default file does).

Create the owner on the server:

```bash
su -s /bin/bash widget -c "cd /mnt/ger_hd1/www/widget/app && php8.4 artisan widget:user you@example.com" <<< 'a-long-password'
su -s /bin/bash widget -c "cd /mnt/ger_hd1/www/widget/app && php8.4 artisan widget:token phone"
```

Run behind HTTPS only (Cloudflare + Apache here). No queue worker is required.
