#!/usr/bin/env bash
# Deploy the last git commit of webapp/ to ger1 (gadget.peppasoft.com).
# Run from the repo root in Git Bash:  bash deploy/deploy.sh
# The first run also creates .env (random APP_KEY, DB password from /root/.gadget-db-pass) and the cron file.
# Server setup (Virtualmin domain, MySQL, PHP-FPM 8.4) is described in webapp/README.md.
set -euo pipefail

HOST=Ger1-root
HOME_DIR=/mnt/ger_hd1/www/gadget
APP=$HOME_DIR/app
SITE_USER=gadget
DOMAIN=gadget.peppasoft.com
STAMP=$(date +%Y%m%d-%H%M%S)

echo "== whitelist IP on the Hetzner cloud firewall"
curl -s "https://mon.peppasoft.com/addip?_type=s" | tail -1
sleep 5

echo "== build archive from HEAD ($(git rev-parse --short HEAD))"
TMP=$(mktemp -d)
git archive --format=tar.gz -o "$TMP/release.tgz" HEAD:webapp
scp -q "$TMP/release.tgz" "$HOST:/tmp/gadget-release.tgz"
rm -rf "$TMP"

ssh "$HOST" bash -s -- "$APP" "$HOME_DIR" "$DOMAIN" "$STAMP" "$SITE_USER" <<'REMOTE'
set -euo pipefail
APP=$1; HOME_DIR=$2; DOMAIN=$3; STAMP=$4; SITE_USER=$5

echo "== backup old app + database"
if [ -f "$APP/artisan" ]; then
  tar czf /root/gadget-backup-$STAMP.tgz -C "$HOME_DIR" --exclude=app/vendor --exclude=app/storage/framework --exclude=app/storage/logs app || true
  mysqldump --single-transaction gadget 2>/dev/null | gzip > /root/gadget-db-$STAMP.sql.gz || true
  ls -1t /root/gadget-backup-*.tgz 2>/dev/null | tail -n +6 | xargs -r rm -f
  ls -1t /root/gadget-db-*.sql.gz 2>/dev/null | tail -n +6 | xargs -r rm -f
fi

echo "== extract (keeps .env, vendor, storage; no .md files)"
STAGE=$(mktemp -d)
tar xzf /tmp/gadget-release.tgz -C "$STAGE"
mkdir -p "$APP"
rsync -a --delete \
  --exclude=/.env --exclude=/vendor --exclude=/storage --exclude=/bootstrap/cache --exclude='*.md' \
  "$STAGE"/ "$APP"/
mkdir -p "$APP"/storage/{app,framework/{cache,sessions,views},logs} "$APP"/bootstrap/cache
rsync -a "$STAGE"/storage/ "$APP"/storage/ --ignore-existing
rm -rf "$STAGE" /tmp/gadget-release.tgz
rm -f "$APP"/bootstrap/cache/packages.php "$APP"/bootstrap/cache/services.php

if [ ! -f "$APP/.env" ]; then
  echo "== first run: create .env"
  PW=$(cat /root/.gadget-db-pass)
  cp "$APP/.env.example" "$APP/.env"
  sed -i -e 's/^APP_ENV=.*/APP_ENV=production/' -e 's/^APP_DEBUG=.*/APP_DEBUG=false/' \
    -e "s#^APP_URL=.*#APP_URL=https://$DOMAIN#" -e 's/^LOG_LEVEL=.*/LOG_LEVEL=warning/' \
    -e 's/^DB_CONNECTION=.*/DB_CONNECTION=mysql/' -e 's/^# *DB_HOST=.*/DB_HOST=127.0.0.1/' \
    -e 's/^# *DB_PORT=.*/DB_PORT=3306/' -e 's/^# *DB_DATABASE=.*/DB_DATABASE=gadget/' \
    -e 's/^# *DB_USERNAME=.*/DB_USERNAME=gadget/' -e "s/^# *DB_PASSWORD=.*/DB_PASSWORD=$PW/" "$APP/.env"
fi
chown -R $SITE_USER:$SITE_USER "$APP"
chmod 710 "$APP"; chmod 755 "$APP/public"; chmod 600 "$APP/.env"

echo "== composer + migrate + cache"
su -s /bin/bash $SITE_USER -c "cd $APP && php8.4 /usr/local/bin/composer install --no-dev --optimize-autoloader --no-interaction --quiet \
  && (grep -q '^APP_KEY=.\+' .env || php8.4 artisan key:generate --force) \
  && php8.4 artisan migrate --force && php8.4 artisan optimize"

echo "== cron (schedule:run every minute, runs the daily cleanup)"
echo "* * * * * $SITE_USER cd $APP && php8.4 artisan schedule:run >> /dev/null 2>&1" > /etc/cron.d/gadget
chmod 644 /etc/cron.d/gadget

echo "== flush opcache of this FPM pool"
F=flush-$STAMP.php
trap 'rm -f "$APP/public/$F"' EXIT
echo '<?php opcache_reset(); echo "flushed";' > "$APP/public/$F"
chown $SITE_USER:$SITE_USER "$APP/public/$F"
curl -sk --max-time 15 --resolve "$DOMAIN:443:162.55.167.140" "https://$DOMAIN/$F" || echo "flush request failed"; echo
rm -f "$APP/public/$F"
REMOTE

echo "== verify"
for i in 1 2 3 4 5; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "https://$DOMAIN/up") && [ "$code" = 200 ] && break
  sleep 1
done
echo "/up -> $code"
echo "/login -> $(curl -s -o /dev/null -w "%{http_code}" "https://$DOMAIN/login")"
